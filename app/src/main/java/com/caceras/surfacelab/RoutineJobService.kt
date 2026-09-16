package com.caceras.surfacelab

import android.app.*
import android.app.job.*
import android.content.*
import android.net.Uri
import javax.net.ssl.HttpsURLConnection

/** One persisted dispatcher. Occurrences are claimed durably before network I/O; uncertain sends never retry. */
class RoutineJobService:JobService() {
    @Volatile private var stopped=false
    @Volatile private var connection:HttpsURLConnection?=null
    override fun onStartJob(params:JobParameters):Boolean {
        stopped=false
        Thread {
            try {
                WorkspaceStore(this).use { store ->
                    val eligible=eligible(this,store).filter { it.due<=System.currentTimeMillis() }.take(3)
                    for(record in eligible) {
                        if(stopped) break
                        val approval=ConnectedAI.routineApproval(store,this,record) ?: continue
                        val run="remote:${record.id}:${record.due}"
                        if(!store.execution(run,record.id,"started","Connected AI · no automatic retry")) continue
                        try {
                            val sources=approval.optString("sources").split(",").mapNotNull(store::get).filterNot { it.deleted }.take(5)
                            val prompt=KnowledgeContext.withSources(record.body,sources)
                            val answer=ConnectedAI.request(this,prompt) { connection=it }
                            if(stopped) { store.executionState(run,"interrupted","Check run history before running manually."); break }
                            val latest=store.get(record.id)
                            if(latest==null || latest.deleted || !latest.enabled || ConnectedAI.routineApproval(store,this,latest)==null) {
                                store.executionState(run,"discarded","Routine changed while generating; no result saved."); continue
                            }
                            val db=store.writableDatabase; db.beginTransaction()
                            var result:Record?=null
                            try {
                                result=store.save(Record(title=record.title.ifBlank { "AI routine" }+" · "+java.time.LocalDate.now(),body=answer.take(200000),source="Connected AI routine ${record.id}"))
                                store.link(result.id,record.id,"source"); store.executionState(run,"completed",result.id)
                                val next=Reminders.nextDue(record,System.currentTimeMillis())
                                store.save(latest.copy(due=next,enabled=if(next==0L) false else latest.enabled))
                                db.setTransactionSuccessful()
                            } finally { db.endTransaction() }
                            result?.let { notifyResult(it.id) }
                        } catch(e:Exception) {
                            store.executionState(run,if(stopped) "interrupted" else "failed",e.message.orEmpty().take(300))
                            val latest=store.get(record.id)
                            if(latest!=null && latest.due==record.due) {
                                val next=Reminders.nextDue(record,System.currentTimeMillis())
                                store.save(latest.copy(due=next,enabled=next>0 && latest.enabled))
                            }
                        } finally { connection=null }
                    }
                }
            } finally {
                if(!stopped) {
                    jobFinished(params,false)
                    android.os.Handler(mainLooper).post { schedule(this) }
                }
            }
        }.start()
        return true
    }
    private fun notifyResult(id:String) {
        if(!Reminders.allowed(this)) return
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Reminders.CHANNEL,"Reminders",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(this,0,WorkspaceActivity.intent(this,"library",id).setData(Uri.Builder().scheme("aegentica").authority("result").appendPath(id).build()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id,2,Notification.Builder(this,Reminders.CHANNEL).setSmallIcon(R.drawable.ic_surface).setContentTitle("Ægentica AI").setContentText("Your AI routine has a result in Library.").setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
    }
    override fun onStopJob(params:JobParameters):Boolean { stopped=true; connection?.disconnect(); return false }
    companion object {
        private const val JOB=8041
        private fun eligible(context:Context,store:WorkspaceStore):List<Record> = store.scheduled().filter { r ->
            r.kind=="routine" && ConnectedAI.routineApproval(store,context,r)!=null && store.readableDatabase.rawQuery("SELECT 1 FROM executions WHERE id=?",arrayOf("remote:${r.id}:${r.due}")).use { !it.moveToFirst() }
        }
        fun schedule(context:Context) {
            val next=WorkspaceStore(context).use { eligible(context,it).minOfOrNull { r -> r.due } }
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(next==null) { scheduler.cancel(JOB); return }
            scheduler.schedule(JobInfo.Builder(JOB,ComponentName(context,RoutineJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency((next-System.currentTimeMillis()).coerceAtLeast(1000)).setPersisted(true).build())
        }
    }
}
