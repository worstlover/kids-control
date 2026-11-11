// Mocks: SUPABASE_URL (proxy to PostgREST + fake storage/auth) on 4000, Anthropic on 4001, store page on 4002
const PGRST = 'http://127.0.0.1:3000';
Deno.serve({ port: 4000, onListen() {} }, async (req) => {
  const u = new URL(req.url);
  if (u.pathname.startsWith('/storage/v1/')) return Response.json({ Key: 'ok' });
  if (u.pathname.startsWith('/auth/v1/user')) {
    return (req.headers.get('authorization') ?? '').includes('parent-jwt')
      ? Response.json({ id: 'u1', email: 'p@x.ir' }) : Response.json({ msg: 'bad' }, { status: 401 });
  }
  if (u.pathname.startsWith('/functions/v1/')) {
    const port = { 'tutor-lesson': 8008, 'fetch-price': 8003, 'device-sync': 8001, 'device-submit': 8002, 'dictation-create': 8004, 'dictation-audio': 8005, 'tutor': 8007 }[u.pathname.split('/')[3] as string];
    return fetch(`http://127.0.0.1:${port}/`, { method: req.method, headers: req.headers, body: await req.arrayBuffer() });
  }
  if (u.pathname.startsWith('/storage/v1/object/sign/')) return Response.json({ signedURL: '/object/sign/x?token=t' });
  const target = PGRST + u.pathname.replace(/^\/rest\/v1/, '') + u.search;
  return fetch(target, { method: req.method, headers: req.headers, body: ['GET', 'HEAD'].includes(req.method) ? undefined : await req.arrayBuffer() });
});
Deno.serve({ port: 4001, onListen() {} }, async (req) => {
  if (req.url.includes('/v1beta/interactions')) {
    if (req.headers.get('x-goog-api-key') !== 'k') return new Response('bad', { status: 403 });
    const b = await req.json();
    const c = b.input?.[0]?.content?.[0];
    const ok = b.model === 'gemini-3.8-flash-tts' && b.response_format?.mime_type === 'audio/l16' && b.response_format?.sample_rate === 24000
      && b.generation_config?.speech_config?.[0]?.voice === 'Kore' && c?.annotations?.[0]?.type === 'speech_metadata' && c?.annotations?.[0]?.style.includes('slowly');
    if (!ok) return new Response('bad tts request ' + JSON.stringify(b).slice(0, 200), { status: 400 });
    if (c.text.includes('خرابی')) return new Response('tts down', { status: 500 });
    return Response.json({ steps: [{ type: 'model_output', content: [{ type: 'audio', data: btoa(unescape(encodeURIComponent(c.text))) }] }] });
  }
  if (!req.url.includes(':generateContent') || req.headers.get('x-goog-api-key') !== 'k') return new Response('bad', { status: 403 });
  const b = await req.json();
  const parts = b.contents[0].parts;
  const img = parts.find((x: any) => x.inline_data);
  const text = parts.find((x: any) => x.text).text;
  console.log('GEM', parts.map((p: any) => p.text ? 'T:' + p.text.slice(0, 70).replace(/\n/g, ' ') : p.inline_data ? 'BIN:' + p.inline_data.mime_type : '?').join(' | '));
  if (parts.some((x: any) => x.inline_data?.mime_type?.startsWith('audio/'))) {
    const a = parts.find((x: any) => x.inline_data);
    const raw = atob(a.inline_data.data).slice(0, 6);
    const reply2 = (o: unknown) => Response.json({ candidates: [{ content: { parts: [{ text: JSON.stringify(o) }] } }] });
    if (raw === 'SILENT') return reply2({ segments: [] });
    return reply2({ segments: [{ text: 'آب و هوا', start: 0, end: 2.5 }, { text: 'امروز خیلی خوب است', start: 3, end: 6 }, { text: 'غلط', start: 5, end: 5.2 }, { text: 'بچه‌ها بازی می‌کنند', start: 7, end: 10 }] });
  }
  if (parts.map((x: any) => x.text ?? '').join('\n').includes('اولین صفحه‌ی یک درس')) {
    const img2 = parts.find((x: any) => x.inline_data);
    const mk = img2 ? atob(img2.inline_data.data).slice(0, 8) : 'PAGETEXT';
    const reply3 = (o: unknown) => Response.json({ candidates: [{ content: { parts: [{ text: JSON.stringify(o) }] } }] });
    if (mk === 'NOTLESSN') return reply3({ readable: false, title: '', sections: [], feedback: 'این صفحه درس نیست' });
    return reply3({ readable: true, title: 'درس ۳: آب', sections: ['آشنایی', 'واژه‌ها', '  ', 'جمع‌بندی'], feedback: '' });
  }
  if (text.includes('[TUTOR_')) {
    const reply = (o: unknown) => Response.json({ candidates: [{ content: { parts: [{ text: JSON.stringify(o) }] } }] });
    if (text.includes('[TUTOR_ASSESS]')) return reply({ questions: [{ q: '۱+۱؟', a: 'دو' }, { q: '۲+۲؟', a: 'چهار' }, { q: '۳+۳؟', a: 'شش' }, { q: '۴+۴؟', a: 'هشت' }, { q: '۵+۵؟', a: 'ده' }] });
    if (text.includes('[TUTOR_GRADE_LEVEL]')) return reply({ level: text.includes('جواب بچه: دو') ? 'متوسط' : 'مبتدی', score: 4, notes: 'جمع ساده خوب است', feedback: 'آفرین!' });
    if (text.includes('[TUTOR_PLAN]')) return reply({ items: [{ title: 'تمرین جمع', type: 'practice', minutes: 5 }, { title: 'توضیح کسر', type: 'explain', minutes: 7 }] });
    if (text.includes('[TUTOR_PRACTICE]') && text.includes('فقط از درس')) return reply({ questions: [1, 2, 3, 4, 5].map(i => ({ q: 'درس ' + i + (text.includes('بخش «آشنایی»') ? ' آشنایی' : ' بخش دیگر'), a: 'l' + i })) });
    if (text.includes('[TUTOR_PRACTICE]')) return reply({ questions: [1, 2, 3, 4, 5].map(i => ({ q: 'پرسش ' + i, a: 'p' + i + 'a' })) });
    if (text.includes('[TUTOR_CHECK]') && text.includes('AIFAIL')) return new Response('boom', { status: 500 });
    if (text.includes('[TUTOR_CHECK]')) return reply({ results: [{ correct: true, hint: '' }, { correct: false, hint: 'دوباره فکر کن' }, { correct: true, hint: '' }, { correct: true, hint: '' }, { correct: true, hint: '' }], notes: 'جمع خوب، تفریق ضعیف' });
    if (text.includes('[TUTOR_STEP_CHECK]')) return reply(text.includes('جواب بچه: درست') ? { correct: true, feedback: 'آفرین', say: null, board: null }
      : { correct: false, feedback: 'نزدیک بودی', say: 'از راه دیگر: نصف یعنی دو تکه‌ی برابر', board: '<svg viewBox="0 0 400 240"><circle cx="200" cy="120" r="60" stroke="#F8F8F2"/><script>x</script></svg>' });
    if (text.includes('[TUTOR_TEACH_BACK]')) return reply(text.includes('تکه') ? { score: 85, covered: ['کسر یعنی تکه'], missing: [], feedback: 'خوب یاد دادی' } : { score: 30, covered: [], missing: ['مخرج'], feedback: 'مخرج را جا انداختی' });
    if (text.includes('[TUTOR_EXIT]')) return reply({ questions: [{ type: 'apply', q: 'e1', a: 'e1a' }, { type: 'find_error', q: 'e2', a: 'e2a' }, { type: 'why', q: 'e3', a: 'e3a' }] });
    if (text.includes('[TUTOR_REVIEW]')) return reply({ questions: [{ q: 'r1', a: 'r1a' }, { q: 'r2', a: 'r2a' }] });
    if (text.includes('[TUTOR_EXPLAIN]')) {
      if (text.includes('خیلی ساده‌تر')) return reply({ title: 'ساده', steps: [{ say: 'مثل یک پیتزا', board: '<svg viewBox="0 0 400 240"><circle cx="200" cy="120" r="80"/></svg>', ask: null }] });
      return reply({ title: 'کسر', steps: [
        { say: 'کسر یعنی یک چیز به چند قسمت مساوی.', board: '<svg viewBox="0 0 400 240"><circle cx="200" cy="120" r="80"/></svg>', ask: 'فهمیدی؟' },
        { say: 'مثلا نصف یعنی دو قسمت، یکی برداشته شده.', board: '<svg viewBox="0 0 400 240"><script>alert(1)</script></svg>', ask: null }] });
    }
    return reply({});
  }
  let out: string;
  if (img) {
    const m = atob(img.inline_data.data).slice(0, 8);
    if (text.includes('متن اصلی دیکته')) {
      if (m === 'APIFAIL!') return new Response('boom', { status: 500 });
      if (!text.includes('1. ')) return Response.json({ candidates: [{ content: { parts: [{ text: 'NO PHRASES' }] } }] });
      const o = m === 'DICTLOW!' ? '{"readable": true, "score": 9, "feedback": "چند غلط داشتی"}' : m === 'BLURRY!!' ? '{"readable": false, "feedback": "تار"}' : '{"readable": true, "score": 17, "feedback": "عالی بود"}';
      return Response.json({ candidates: [{ content: { parts: [{ text: o }] } }] });
    }
    if (text.includes('صفحه‌ی کتاب')) {
      if (m === 'APIFAIL!') return new Response('boom', { status: 500 });
      const o = m === 'NOTEXT!!' ? '{"readable": false, "title": "", "phrases": [], "feedback": "صفحه معلوم نیست"}'
        : '{"readable": true, "title": "درس سوم", "phrases": ["آب و هوا", "امروز خیلی خوب است", "  ", "بچه‌ها در حیاط بازی می‌کنند"], "feedback": ""}';
      return Response.json({ candidates: [{ content: { parts: [{ text: o }] } }] });
    }
    if (m === 'APIFAIL!') return new Response('boom', { status: 500 });
    if (m === 'BLURRY!!') out = '{"readable": false, "score": 0, "feedback": "عکس تار است"}';
    else if (m === 'SCORE10!') out = '{"readable": true, "score": 10, "feedback": "چند غلط داشتی"}';
    else out = '{"readable": true, "score": 18, "feedback": "آفرین، فقط یک غلط"}';
    if (!b.systemInstruction?.parts[0].text.includes('JSON') || b.generationConfig.responseMimeType !== 'application/json') out = 'CONFIG MISSING';
  } else if (b.tools?.[0]?.google_search) {
    out = 'جست‌وجو کردم. {"title":"دوچرخه ۲۰","price_toman":"۴٬۲۰۰٬۰۰۰","image_url":"https://img.x.ir/b.jpg","source_url":"https://shop.x.ir/b"}';
  } else {
    out = '{"title":"AI title","price_toman":1250000,"image_url":null,"source_url":null}';
    if (!text.includes('قیمت')) out = 'bad';
  }
  return Response.json({ candidates: [{ content: { parts: [{ text: 'thinking...', thought: true }, { text: out }] } }] });
});
Deno.serve({ port: 4002, onListen() {} }, () => new Response(
  '<html><head><meta property="og:title" content="لگو سیتی"><meta property="og:image" content="https://cdn.x.ir/lego.jpg"></head><body>قیمت ۱٬۲۵۰٬۰۰۰ تومان</body></html>',
  { headers: { 'content-type': 'text/html' } }));
// fake textbook site; reached as http://book.sch.ir/... through an /etc/hosts entry (see run.sh)
Deno.serve({ port: 80, hostname: '127.0.0.1', onListen() {} }, (req) => {
  const u = new URL(req.url);
  if (u.pathname === '/lesson3') return new Response('<html><head><title>درس ۳</title></head><body>درس سوم: آب<video src="/media/l3.mp4"></video><a href="/media/l3.mp3">فایل صوتی</a></body></html>', { headers: { 'content-type': 'text/html' } });
  if (u.pathname === '/redir') return new Response(null, { status: 302, headers: { location: '/lesson3' } });
  if (u.pathname === '/media/l3.mp3') return new Response(new Uint8Array(3000).fill(65), { headers: { 'content-type': 'audio/mpeg' } });
  if (u.pathname === '/media/silent.mp3') return new Response(new TextEncoder().encode('SILENT' + 'x'.repeat(3000)), { headers: { 'content-type': 'audio/mpeg' } });
  if (u.pathname === '/media/big.mp3') return new Response(new Uint8Array(13_000_000), { headers: { 'content-type': 'audio/mpeg' } });
  if (u.pathname === '/media/doc.mp3') return new Response('hello', { headers: { 'content-type': 'text/plain' } });
  return new Response('not found', { status: 404 });
});
await new Promise(() => {});
