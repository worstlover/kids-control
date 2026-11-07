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
    const port = { 'fetch-price': 8003, 'device-sync': 8001, 'device-submit': 8002, 'dictation-create': 8004, 'dictation-audio': 8005 }[u.pathname.split('/')[3] as string];
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
await new Promise(() => {});
