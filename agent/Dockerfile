# Ægentica's LiveKit voice agent and its token endpoint (same image, two commands).
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY aegentica_agent ./aegentica_agent
# Bake in the voice-activity model so a new conversation does not wait for a download.
RUN python -m aegentica_agent.agent download-files
RUN useradd --uid 10001 --no-create-home agent
USER agent
# Default: the agent worker (outbound connection to LiveKit, no inbound ports).
CMD ["python", "-m", "aegentica_agent.agent", "start"]
