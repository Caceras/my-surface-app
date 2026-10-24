/*
 * Ægentica's hands inside the sheet's browser: read a page, list what can be clicked or typed
 * into (with refs like e12, as in Glimt's snapshot), find words, scroll, click and type. Loaded
 * into the page only when the phone agent calls a web tool; it reports what it finds and changes
 * only what the call asks. Password, card and one-time-code values are never read or typed.
 */
(function () {
  if (window.__aeg) return;
  var SECRET = /pass(word|code|phrase)?|lösen|pin\b|cvc|cvv|card.?num|kortnummer|one.?time|otp|verification.?code|säkerhetskod|engångskod|2fa|totp/i;
  var next = 1;
  function ref(el) {
    var r = el.getAttribute('data-aeg-ref');
    if (!r) { r = 'e' + next++; el.setAttribute('data-aeg-ref', r); }
    return r;
  }
  function byRef(r) {
    r = String(r || '').replace(/^@/, '');
    return document.querySelector('[data-aeg-ref="' + r.replace(/[^a-z0-9]/gi, '') + '"]');
  }
  function visible(el) {
    var b = el.getBoundingClientRect();
    if (b.width < 1 || b.height < 1) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05;
  }
  function clean(t, max) { return String(t || '').replace(/\s+/g, ' ').trim().slice(0, max || 120); }
  function secret(el) {
    if (!el || !el.tagName) return false;
    var type = (el.getAttribute('type') || '').toLowerCase();
    var auto = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (type === 'password' || /cc-|one-time-code|current-password|new-password/.test(auto)) return true;
    var words = [el.name, el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label'), labelOf(el)].join(' ');
    return SECRET.test(words);
  }
  function labelOf(el) {
    if (el.labels && el.labels[0]) return clean(el.labels[0].innerText);
    var id = el.getAttribute('aria-labelledby');
    if (id) { var l = document.getElementById(id); if (l) return clean(l.innerText); }
    return '';
  }
  function role(el) {
    var r = el.getAttribute('role');
    if (r) return r;
    var t = el.tagName.toLowerCase();
    if (t === 'a') return 'link';
    if (t === 'button') return 'button';
    if (t === 'select') return 'select';
    if (t === 'textarea') return 'field';
    if (t === 'input') {
      var type = (el.getAttribute('type') || 'text').toLowerCase();
      if (type === 'checkbox' || type === 'radio') return type;
      if (type === 'submit' || type === 'button' || type === 'reset') return 'button';
      return 'field';
    }
    if (el.isContentEditable) return 'field';
    return 'button';
  }
  function name(el) {
    return clean(el.getAttribute('aria-label') || labelOf(el) || el.innerText || el.value || el.getAttribute('placeholder') ||
      el.getAttribute('title') || el.getAttribute('alt') || el.name || '', 80);
  }
  var INTERACTIVE = 'a[href],button,input:not([type=hidden]),select,textarea,[role=button],[role=link],[role=checkbox],[role=tab],[role=menuitem],[role=option],[contenteditable=true],[onclick]';
  function describe(el) {
    var r = role(el), n = name(el), parts = [ref(el), r, '“' + n + '”'];
    if (r === 'field') parts.push('value=“' + (secret(el) ? '••••' : clean(el.value != null ? el.value : el.innerText, 60)) + '”');
    if (r === 'checkbox' || r === 'radio') parts.push(el.checked ? '[checked]' : '[not checked]');
    if (secret(el)) parts.push('[secret]');
    if (el.disabled) parts.push('[disabled]');
    if (r === 'link' && el.href) parts.push('→ ' + el.href.slice(0, 160));
    return parts.join(' ');
  }
  window.__aeg = {
    snapshot: function () {
      var all = Array.prototype.slice.call(document.querySelectorAll(INTERACTIVE)).filter(visible);
      var h = window.innerHeight;
      all.sort(function (a, b) {
        var ia = a.getBoundingClientRect().top < h ? 0 : 1, ib = b.getBoundingClientRect().top < h ? 0 : 1;
        return ia - ib;
      });
      var lines = all.slice(0, 150).map(describe);
      return JSON.stringify({ title: document.title, url: location.href, lines: lines, more: Math.max(0, all.length - 150) });
    },
    read: function (offset) {
      var root = document.querySelector('main') || document.querySelector('article') || document.body;
      var text = clean(root ? root.innerText : '', 1e7);
      // Field values never leave as text: inputs are not part of innerText.
      offset = Math.max(0, offset | 0);
      var size = 6000;
      return JSON.stringify({ title: document.title, url: location.href, text: text.slice(offset, offset + size), next: offset + size < text.length ? offset + size : null, total: text.length });
    },
    find: function (query) {
      query = String(query || '').toLowerCase();
      if (!query) return JSON.stringify({ hits: [] });
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), hits = [], node;
      while ((node = walker.nextNode()) && hits.length < 20) {
        var t = node.nodeValue, i = t.toLowerCase().indexOf(query);
        if (i < 0 || !node.parentElement || !visible(node.parentElement)) continue;
        var el = node.parentElement.closest(INTERACTIVE) || node.parentElement;
        hits.push(ref(el) + ' “' + clean(t.slice(Math.max(0, i - 60), i + query.length + 60), 160) + '”');
      }
      return JSON.stringify({ hits: hits });
    },
    about: function (r) {
      var el = byRef(r);
      if (!el) return JSON.stringify({ found: false });
      return JSON.stringify({ found: true, role: role(el), name: name(el), secret: secret(el), id: el.id || '', type: (el.getAttribute('type') || '').toLowerCase(), form: !!el.form });
    },
    scroll: function (target) {
      var el = byRef(target);
      if (el) el.scrollIntoView({ block: 'center' });
      else if (target === 'top') window.scrollTo(0, 0);
      else if (target === 'bottom') window.scrollTo(0, document.body.scrollHeight);
      else window.scrollBy(0, (target === 'up' ? -0.8 : 0.8) * window.innerHeight);
      return JSON.stringify({ y: Math.round(window.scrollY), height: document.body.scrollHeight });
    },
    mark: function (r) {
      var el = byRef(r);
      if (!el) return 'false';
      el.scrollIntoView({ block: 'center' });
      var before = el.style.outline;
      el.style.outline = '3px solid #111114';
      el.style.outlineOffset = '2px';
      setTimeout(function () { el.style.outline = before; }, 1200);
      return 'true';
    },
    click: function (r) {
      var el = byRef(r);
      if (!el) return JSON.stringify({ ok: false, error: 'That element is gone. Take a new snapshot.' });
      el.scrollIntoView({ block: 'center' });
      el.click();
      return JSON.stringify({ ok: true });
    },
    type: function (r, text, submit, clear) {
      var el = byRef(r);
      if (!el) return JSON.stringify({ ok: false, error: 'That field is gone. Take a new snapshot.' });
      if (secret(el)) return JSON.stringify({ ok: false, secret: true });
      el.focus();
      if (el.isContentEditable) {
        el.textContent = clear ? text : el.textContent + text;
      } else {
        var proto = Object.getPrototypeOf(el), d = Object.getOwnPropertyDescriptor(proto, 'value');
        var value = clear ? text : (el.value || '') + text;
        if (d && d.set) d.set.call(el, value); else el.value = value;
      }
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      if (submit) {
        if (el.form && el.form.requestSubmit) el.form.requestSubmit();
        else ['keydown', 'keypress', 'keyup'].forEach(function (k) { el.dispatchEvent(new KeyboardEvent(k, { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true })); });
      }
      return JSON.stringify({ ok: true });
    },
    select: function (r, value) {
      var el = byRef(r);
      if (!el || el.tagName.toLowerCase() !== 'select') return JSON.stringify({ ok: false, error: 'That is not a list to choose from.' });
      value = String(value || '').toLowerCase();
      var opt = Array.prototype.find.call(el.options, function (o) { return o.value.toLowerCase() === value || clean(o.text).toLowerCase() === value; });
      if (!opt) return JSON.stringify({ ok: false, error: 'No such choice.' });
      el.value = opt.value;
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return JSON.stringify({ ok: true, chosen: clean(opt.text) });
    }
  };
})();
