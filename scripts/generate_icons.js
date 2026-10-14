#!/usr/bin/env node

/**
 * OpenTouchpad Android Icon Generator
 * Generates all mipmap densities (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
 * for ic_launcher.png and ic_launcher_round.png.
 */

const fs = require('fs');
const path = require('path');

let createCanvas;
try {
  ({ createCanvas } = require('@napi-rs/canvas'));
} catch (e) {
  try {
    ({ createCanvas } = require('/root/.gemini/antigravity-cli/brain/066a8aee-5b3e-49f4-b907-277cff6b3bae/scratch/node_modules/@napi-rs/canvas'));
  } catch (err) {
    console.error('Error: Please install @napi-rs/canvas via "npm install @napi-rs/canvas"');
    process.exit(1);
  }
}

const TC = '#E05A36';   // 陶土橙 (Terracotta Orange)
const WHITE = '#FFFFFF';

/**
 * Core Canvas Drawing Routine for OpenTouchpad
 */
function drawOpenTouchpadIcon(ctx, size, options = {}) {
  const { shape = 'squircle', style = 'tc_bg_wave' } = options;
  const s = size / 512;
  ctx.save();
  ctx.clearRect(0, 0, size, size);
  ctx.scale(s, s);

  // 1. Clip Icon Shape
  ctx.beginPath();
  if (shape === 'circle') {
    ctx.arc(256, 256, 238, 0, Math.PI * 2);
  } else if (shape === 'squircle') {
    ctx.roundRect(16, 16, 480, 480, 108);
  } else {
    ctx.rect(0, 0, 512, 512);
  }
  ctx.clip();

  if (style === 'tc_bg_wave') {
    // 方案 A: 陶土橙底白描 (手势波浪★推荐)
    ctx.fillStyle = TC;
    ctx.fillRect(0, 0, 512, 512);

    ctx.strokeStyle = WHITE;
    ctx.fillStyle = WHITE;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const ox = 256, oy = 126;
    ctx.lineWidth = 26;
    ctx.beginPath();
    ctx.arc(ox, oy, 48, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(ox, oy, 20, 0, Math.PI * 2);
    ctx.fill();

    const px = 86, py = 206, pw = 340, ph = 222, pr = 38;
    ctx.lineWidth = 28;
    ctx.beginPath();
    ctx.roundRect(px, py, pw, ph, pr);
    ctx.stroke();

    const dy = py + ph - 56;
    ctx.lineWidth = 24;
    ctx.beginPath();
    ctx.moveTo(px + 18, dy);
    ctx.lineTo(px + pw - 18, dy);
    ctx.moveTo(256, dy);
    ctx.lineTo(256, py + ph - 10);
    ctx.stroke();

    ctx.lineWidth = 26;
    ctx.beginPath();
    ctx.moveTo(136, 305);
    ctx.bezierCurveTo(170, 245, 210, 245, 256, 288);
    ctx.bezierCurveTo(300, 330, 340, 270, 376, 260);
    ctx.stroke();

  } else if (style === 'tc_white_wave') {
    // 方案 B: 白底陶土橙 (手势波浪)
    ctx.fillStyle = WHITE;
    ctx.fillRect(0, 0, 512, 512);

    ctx.strokeStyle = TC;
    ctx.fillStyle = TC;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const ox = 256, oy = 126;
    ctx.lineWidth = 26;
    ctx.beginPath();
    ctx.arc(ox, oy, 48, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(ox, oy, 20, 0, Math.PI * 2);
    ctx.fill();

    const px = 86, py = 206, pw = 340, ph = 222, pr = 38;
    ctx.lineWidth = 28;
    ctx.beginPath();
    ctx.roundRect(px, py, pw, ph, pr);
    ctx.stroke();

    const dy = py + ph - 56;
    ctx.lineWidth = 24;
    ctx.beginPath();
    ctx.moveTo(px + 18, dy);
    ctx.lineTo(px + pw - 18, dy);
    ctx.moveTo(256, dy);
    ctx.lineTo(256, py + ph - 10);
    ctx.stroke();

    ctx.lineWidth = 26;
    ctx.beginPath();
    ctx.moveTo(136, 305);
    ctx.bezierCurveTo(170, 245, 210, 245, 256, 288);
    ctx.bezierCurveTo(300, 330, 340, 270, 376, 260);
    ctx.stroke();

  } else if (style === 'tc_bg_clean') {
    // 方案 C: 陶土橙底纯净
    ctx.fillStyle = TC;
    ctx.fillRect(0, 0, 512, 512);

    ctx.strokeStyle = WHITE;
    ctx.fillStyle = WHITE;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const ox = 256, oy = 138;
    ctx.lineWidth = 28;
    ctx.beginPath();
    ctx.arc(ox, oy, 54, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(ox, oy, 24, 0, Math.PI * 2);
    ctx.fill();

    const px = 92, py = 224, pw = 328, ph = 208, pr = 38;
    ctx.lineWidth = 28;
    ctx.beginPath();
    ctx.roundRect(px, py, pw, ph, pr);
    ctx.stroke();

    const dy = py + ph - 56;
    ctx.lineWidth = 24;
    ctx.beginPath();
    ctx.moveTo(px + 18, dy);
    ctx.lineTo(px + pw - 18, dy);
    ctx.moveTo(256, dy);
    ctx.lineTo(256, py + ph - 10);
    ctx.stroke();

  } else if (style === 'tc_white_clean') {
    // 方案 D: 白底陶土橙纯净
    ctx.fillStyle = WHITE;
    ctx.fillRect(0, 0, 512, 512);

    ctx.strokeStyle = TC;
    ctx.fillStyle = TC;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const ox = 256, oy = 138;
    ctx.lineWidth = 28;
    ctx.beginPath();
    ctx.arc(ox, oy, 54, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(ox, oy, 24, 0, Math.PI * 2);
    ctx.fill();

    const px = 92, py = 224, pw = 328, ph = 208, pr = 38;
    ctx.lineWidth = 28;
    ctx.beginPath();
    ctx.roundRect(px, py, pw, ph, pr);
    ctx.stroke();

    const dy = py + ph - 56;
    ctx.lineWidth = 24;
    ctx.beginPath();
    ctx.moveTo(px + 18, dy);
    ctx.lineTo(px + pw - 18, dy);
    ctx.moveTo(256, dy);
    ctx.lineTo(256, py + ph - 10);
    ctx.stroke();

  } else {
    // 默认为暗黑 3D 光感
    const bgGrad = ctx.createLinearGradient(40, 20, 472, 492);
    bgGrad.addColorStop(0, '#151928');
    bgGrad.addColorStop(0.5, '#0E111C');
    bgGrad.addColorStop(1, '#07090F');
    ctx.fillStyle = bgGrad;
    ctx.fillRect(0, 0, 512, 512);

    const padW = shape === 'circle' ? 320 : 344;
    const padH = shape === 'circle' ? 236 : 248;
    const padX = (512 - padW) / 2;
    const padY = shape === 'circle' ? 188 : 180;
    const padR = 26;

    const padGrad = ctx.createLinearGradient(256, padY, 256, padY + padH);
    padGrad.addColorStop(0, '#2D364D');
    padGrad.addColorStop(0.35, '#222B3F');
    padGrad.addColorStop(0.85, '#161C2B');
    padGrad.addColorStop(1, '#10141E');
    ctx.fillStyle = padGrad;
    ctx.beginPath();
    ctx.roundRect(padX, padY, padW, padH, padR);
    ctx.fill();

    const orbX = 256;
    const orbY = shape === 'circle' ? 156 : 152;
    ctx.save();
    ctx.fillStyle = '#38BDF8';
    ctx.beginPath();
    ctx.arc(orbX, orbY, 36, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  ctx.restore();
}

const DENSITIES = [
  { folder: 'mipmap-mdpi', size: 48 },
  { folder: 'mipmap-hdpi', size: 72 },
  { folder: 'mipmap-xhdpi', size: 96 },
  { folder: 'mipmap-xxhdpi', size: 144 },
  { folder: 'mipmap-xxxhdpi', size: 192 },
];

const RES_DIR = path.resolve(__dirname, '../app/src/main/res');

function generateAllIcons(style = 'tc_bg_wave') {
  console.log(`Generating OpenTouchpad App Icons (Style: ${style})...`);

  for (const { folder, size } of DENSITIES) {
    const dir = path.join(RES_DIR, folder);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }

    // 1. Standard Squircle Icon
    const sqCanvas = createCanvas(size, size);
    drawOpenTouchpadIcon(sqCanvas.getContext('2d'), size, { shape: 'squircle', style });
    const sqPath = path.join(dir, 'ic_launcher.png');
    fs.writeFileSync(sqPath, sqCanvas.toBuffer('image/png'));
    console.log(`[OK] ${folder}/ic_launcher.png (${size}x${size})`);

    // 2. Round Icon
    const rdCanvas = createCanvas(size, size);
    drawOpenTouchpadIcon(rdCanvas.getContext('2d'), size, { shape: 'circle', style });
    const rdPath = path.join(dir, 'ic_launcher_round.png');
    fs.writeFileSync(rdPath, rdCanvas.toBuffer('image/png'));
    console.log(`[OK] ${folder}/ic_launcher_round.png (${size}x${size})`);
  }

  // 3. Play Store 512x512 Master Icon
  const storeCanvas = createCanvas(512, 512);
  drawOpenTouchpadIcon(storeCanvas.getContext('2d'), 512, { shape: 'squircle', style });
  const storePath = path.resolve(__dirname, '../ic_launcher-playstore-512.png');
  fs.writeFileSync(storePath, storeCanvas.toBuffer('image/png'));
  console.log(`[OK] ic_launcher-playstore-512.png (512x512)`);

  console.log(`\nAll application icons successfully updated with style '${style}'!`);
}

if (require.main === module) {
  const targetStyle = process.argv[2] || 'tc_bg_wave';
  generateAllIcons(targetStyle);
}

module.exports = { drawOpenTouchpadIcon, generateAllIcons };
