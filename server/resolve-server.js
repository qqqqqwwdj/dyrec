/**
 * 抖音直播直链解析服务 —— 模板
 *
 * 已实现（合法部分）：
 *   - 接收 App 传来的分享链接，展开短链、提取 room_id / web_rid
 * 需要你补的一格：
 *   - fetchStreams()：调用抖音 webcast 接口并处理签名，返回多档直链
 *     ★ 签名算法（a_bogus / X-Bogus）属于平台反爬保护，本模板不提供实现，
 *       请你自行实现，或改为调用你已获授权的第三方解析接口。
 *
 * 启动：npm i && npm start       默认 http://localhost:8080
 * 试跑：node resolve-server.js 后，浏览器打开
 *        http://localhost:8080/resolve?url=<URL编码的分享链接>
 *
 * 返回契约（App 认这个格式）：
 *   {"roomId":"...","title":"...","streams":[
 *      {"quality":"原画","url":"https://...flv"},
 *      {"quality":"蓝光","url":"https://...flv"}
 *   ]}
 */

const express = require('express');
const app = express();
const PORT = process.env.PORT || 8080;

const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36';

// ---- 1. 展开短链，拿最终地址 ----
async function expand(url) {
  try {
    const r = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': UA } });
    return r.url || url;
  } catch (e) {
    return url;
  }
}

// ---- 2. 提取房间标识 ----
function extractId(finalUrl) {
  let m = finalUrl.match(/live\.douyin\.com\/(\d+)/);
  if (m) return { webRid: m[1] };
  m = finalUrl.match(/[?&]room_id=(\d+)/);
  if (m) return { roomId: m[1] };
  m = finalUrl.match(/[?&]web_rid=([0-9A-Za-z_-]+)/);
  if (m) return { webRid: m[1] };
  return {};
}

// ---- 3. 换播放直链（★ 需要你实现签名） ----
async function fetchStreams(id) {
  // 演示用：如果你在环境变量里放了 DEMO_STREAM，就直接返回它，方便先打通录制链路
  if (process.env.DEMO_STREAM) {
    return [{ quality: '原画', url: process.env.DEMO_STREAM }];
  }

  // TODO: 在这里实现：
  //   1) 用 id.roomId / id.webRid 调用 webcast 的 reflow / info 接口
  //   2) 处理 a_bogus 签名（本模板不提供）
  //   3) 从返回的 stream_url.flv_pull_url / hls_pull_url_map 里取出 原画/蓝光/高清 地址
  //   4) 按清晰度组装成 [{quality, url}] 返回
  //
  // 未实现时返回空数组，App 会提示“无可用直链”。
  return [];
}

app.get('/resolve', async (req, res) => {
  const url = req.query.url;
  if (!url) return res.status(400).json({ note: '缺少 url 参数' });

  const finalUrl = await expand(url);
  const id = extractId(finalUrl);
  const streams = await fetchStreams(id);

  res.json({
    roomId: id.roomId || id.webRid || null,
    title: null,
    streams,
    note: streams.length ? null : '解析服务未实现签名，或未取到直链'
  });
});

app.listen(PORT, () => {
  console.log(`resolver 服务已启动: http://localhost:${PORT}/resolve?url=<分享链接>`);
});
