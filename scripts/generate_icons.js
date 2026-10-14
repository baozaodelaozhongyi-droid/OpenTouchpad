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

/**
 * Core Canvas Drawing Routine for OpenTouchpad
 * @param {CanvasRenderingContext2D} ctx 
 * @param {number} size - Target canvas width & height
 * @param {object} options - { shape: 'squircle'|'circle'|'full', style: 'luminous'|'minimal'|'cyber' }
 */
function drawOpenTouchpadIcon(ctx, size, options = {}) {
  const { shape = 'squircle', style = 'luminous' } = options;
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

  // 2. Background Gradient - Deep rich midnight navy/slate
  const bgGrad = ctx.createLinearGradient(40, 20, 472, 492);
  bgGrad.addColorStop(0, '#151928');
  bgGrad.addColorStop(0.5, '#0E111C');
  bgGrad.addColorStop(1, '#07090F');
  ctx.fillStyle = bgGrad;
  ctx.fillRect(0, 0, 512, 512);

  // Ambient radial glow behind the elements
  const ambientGlow = ctx.createRadialGradient(256, 230, 20, 256, 230, 290);
  ambientGlow.addColorStop(0, 'rgba(56, 189, 248, 0.16)');
  ambientGlow.addColorStop(0.5, 'rgba(37, 99, 235, 0.06)');
  ambientGlow.addColorStop(1, 'rgba(0, 0, 0, 0)');
  ctx.fillStyle = ambientGlow;
  ctx.fillRect(0, 0, 512, 512);

  // Background subtle inner rim light
  if (shape === 'squircle') {
    ctx.save();
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.08)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.roundRect(16, 16, 480, 480, 108);
    ctx.stroke();
    ctx.restore();
  } else if (shape === 'circle') {
    ctx.save();
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.08)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(256, 256, 238, 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  }

  // 3. Touchpad Dimensions (Harmonized for both squircle & circle)
  const isCircle = shape === 'circle';
  const padW = isCircle ? 320 : 344;
  const padH = isCircle ? 236 : 248;
  const padX = (512 - padW) / 2;
  const padY = isCircle ? 188 : 180;
  const padR = 26;

  // Touchpad drop shadow onto background
  ctx.save();
  ctx.shadowColor = 'rgba(0, 0, 0, 0.72)';
  ctx.shadowBlur = 32;
  ctx.shadowOffsetY = 16;
  ctx.fillStyle = '#1D253A';
  ctx.beginPath();
  ctx.roundRect(padX, padY, padW, padH, padR);
  ctx.fill();
  ctx.restore();

  // Touchpad surface body - sleek dark metallic glass
  ctx.save();
  const padGrad = ctx.createLinearGradient(256, padY, 256, padY + padH);
  padGrad.addColorStop(0, '#2D364D');
  padGrad.addColorStop(0.35, '#222B3F');
  padGrad.addColorStop(0.85, '#161C2B');
  padGrad.addColorStop(1, '#10141E');
  ctx.fillStyle = padGrad;
  ctx.beginPath();
  ctx.roundRect(padX, padY, padW, padH, padR);
  ctx.fill();

  // Touchpad outer rim bevel stroke (Crisp metallic edge highlight)
  const rimGrad = ctx.createLinearGradient(padX, padY, padX, padY + padH);
  rimGrad.addColorStop(0, 'rgba(255, 255, 255, 0.48)');
  rimGrad.addColorStop(0.3, 'rgba(125, 211, 252, 0.38)');
  rimGrad.addColorStop(0.8, 'rgba(255, 255, 255, 0.14)');
  rimGrad.addColorStop(1, 'rgba(255, 255, 255, 0.06)');
  ctx.strokeStyle = rimGrad;
  ctx.lineWidth = 2.5;
  ctx.stroke();

  // Inner subtle inset border
  ctx.strokeStyle = 'rgba(0, 0, 0, 0.35)';
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.roundRect(padX + 2, padY + 2, padW - 4, padH - 4, padR - 2);
  ctx.stroke();

  // Touchpad Bottom Click Button area
  const divY = padY + padH - 62;
  
  // Bottom button section subtle highlight
  const btnGrad = ctx.createLinearGradient(256, divY, 256, padY + padH);
  btnGrad.addColorStop(0, 'rgba(255, 255, 255, 0.04)');
  btnGrad.addColorStop(1, 'rgba(0, 0, 0, 0.16)');
  ctx.fillStyle = btnGrad;
  ctx.beginPath();
  ctx.roundRect(padX + 4, divY, padW - 8, padH - (divY - padY) - 4, [0, 0, padR - 4, padR - 4]);
  ctx.fill();

  // Horizontal divider line
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.24)';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(padX + 16, divY);
  ctx.lineTo(padX + padW - 16, divY);
  ctx.stroke();

  // Vertical divider line (between left and right click)
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.24)';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(256, divY);
  ctx.lineTo(256, padY + padH - 12);
  ctx.stroke();

  // Capacitive Touch Ripple Rings on Pad Surface
  const rippleY = isCircle ? 276 : 270;
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.36)';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.ellipse(256, rippleY, 58, 26, 0, 0, Math.PI * 2);
  ctx.stroke();

  ctx.strokeStyle = 'rgba(56, 189, 248, 0.16)';
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.ellipse(256, rippleY, 102, 45, 0, 0, Math.PI * 2);
  ctx.stroke();

  ctx.restore();

  // 4. Floating Ball Shadow on Touchpad (悬浮球在触控板上的投影)
  const orbX = 256;
  const orbY = isCircle ? 156 : 152;

  ctx.save();
  const shadowGrad = ctx.createRadialGradient(256, orbY + 78, 6, 256, orbY + 78, 62);
  shadowGrad.addColorStop(0, 'rgba(0, 0, 0, 0.72)');
  shadowGrad.addColorStop(0.45, 'rgba(0, 0, 0, 0.36)');
  shadowGrad.addColorStop(1, 'rgba(0, 0, 0, 0)');
  ctx.fillStyle = shadowGrad;
  ctx.beginPath();
  ctx.ellipse(256, orbY + 78, 62, 17, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  // 5. Floating Ball (悬浮球主体)
  ctx.save();

  // (a) Luminous Aura Glow
  const orbAura = ctx.createRadialGradient(orbX, orbY, 15, orbX, orbY, 95);
  orbAura.addColorStop(0, 'rgba(56, 189, 248, 0.55)');
  orbAura.addColorStop(0.4, 'rgba(14, 165, 233, 0.22)');
  orbAura.addColorStop(1, 'rgba(14, 165, 233, 0)');
  ctx.fillStyle = orbAura;
  ctx.beginPath();
  ctx.arc(orbX, orbY, 95, 0, Math.PI * 2);
  ctx.fill();

  // (b) Outer Assistive Ring (Frosted glass halo)
  const outerR = 66;
  ctx.save();
  ctx.shadowColor = 'rgba(14, 165, 233, 0.65)';
  ctx.shadowBlur = 24;

  const haloGrad = ctx.createLinearGradient(orbX - outerR, orbY - outerR, orbX + outerR, orbY + outerR);
  haloGrad.addColorStop(0, 'rgba(255, 255, 255, 0.28)');
  haloGrad.addColorStop(0.4, 'rgba(56, 189, 248, 0.18)');
  haloGrad.addColorStop(1, 'rgba(14, 165, 233, 0.24)');
  ctx.fillStyle = haloGrad;

  ctx.beginPath();
  ctx.arc(orbX, orbY, outerR, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  // Halo outer rim stroke
  const haloRim = ctx.createLinearGradient(orbX - outerR, orbY - outerR, orbX + outerR, orbY + outerR);
  haloRim.addColorStop(0, 'rgba(255, 255, 255, 0.88)');
  haloRim.addColorStop(0.35, 'rgba(125, 211, 252, 0.72)');
  haloRim.addColorStop(0.7, 'rgba(56, 189, 248, 0.42)');
  haloRim.addColorStop(1, 'rgba(14, 165, 233, 0.3)');
  ctx.strokeStyle = haloRim;
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.arc(orbX, orbY, outerR, 0, Math.PI * 2);
  ctx.stroke();

  // (c) Middle Precision Ring
  ctx.strokeStyle = 'rgba(186, 230, 253, 0.55)';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(orbX, orbY, 49, 0, Math.PI * 2);
  ctx.stroke();

  // (d) Core Spherical Floating Ball (3D illuminated sphere)
  const coreR = 36;
  const sphereGrad = ctx.createRadialGradient(
    orbX - 11, orbY - 11, 4,
    orbX, orbY, coreR
  );
  sphereGrad.addColorStop(0, '#F0F9FF');
  sphereGrad.addColorStop(0.2, '#67E8F9');
  sphereGrad.addColorStop(0.5, '#0EA5E9');
  sphereGrad.addColorStop(0.85, '#2563EB');
  sphereGrad.addColorStop(1, '#1E3A8A');

  ctx.save();
  ctx.shadowColor = 'rgba(2, 132, 199, 0.75)';
  ctx.shadowBlur = 18;
  ctx.fillStyle = sphereGrad;
  ctx.beginPath();
  ctx.arc(orbX, orbY, coreR, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  // Core rim stroke
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.5)';
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.arc(orbX, orbY, coreR, 0, Math.PI * 2);
  ctx.stroke();

  // (e) Center Precision Touch Dot (Assistive Touch core)
  ctx.save();
  ctx.fillStyle = '#FFFFFF';
  ctx.shadowColor = 'rgba(255, 255, 255, 1)';
  ctx.shadowBlur = 10;
  ctx.beginPath();
  ctx.arc(orbX, orbY, 8, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  // (f) Specular Light Highlight
  ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
  ctx.beginPath();
  ctx.ellipse(orbX - 13, orbY - 13, 7, 4, -Math.PI / 4, 0, Math.PI * 2);
  ctx.fill();

  ctx.restore();
  ctx.restore();
}

// Generate all target mipmap files
const DENSITIES = [
  { folder: 'mipmap-mdpi', size: 48 },
  { folder: 'mipmap-hdpi', size: 72 },
  { folder: 'mipmap-xhdpi', size: 96 },
  { folder: 'mipmap-xxhdpi', size: 144 },
  { folder: 'mipmap-xxxhdpi', size: 192 },
];

const RES_DIR = path.resolve(__dirname, '../app/src/main/res');

function generateAllIcons() {
  console.log('Generating OpenTouchpad App Icons...');

  for (const { folder, size } of DENSITIES) {
    const dir = path.join(RES_DIR, folder);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }

    // 1. Standard Squircle Icon
    const sqCanvas = createCanvas(size, size);
    drawOpenTouchpadIcon(sqCanvas.getContext('2d'), size, { shape: 'squircle' });
    const sqPath = path.join(dir, 'ic_launcher.png');
    fs.writeFileSync(sqPath, sqCanvas.toBuffer('image/png'));
    console.log(`[OK] ${folder}/ic_launcher.png (${size}x${size})`);

    // 2. Round Icon
    const rdCanvas = createCanvas(size, size);
    drawOpenTouchpadIcon(rdCanvas.getContext('2d'), size, { shape: 'circle' });
    const rdPath = path.join(dir, 'ic_launcher_round.png');
    fs.writeFileSync(rdPath, rdCanvas.toBuffer('image/png'));
    console.log(`[OK] ${folder}/ic_launcher_round.png (${size}x${size})`);
  }

  // 3. Play Store 512x512 Master Icon
  const storeCanvas = createCanvas(512, 512);
  drawOpenTouchpadIcon(storeCanvas.getContext('2d'), 512, { shape: 'squircle' });
  const storePath = path.resolve(__dirname, '../ic_launcher-playstore-512.png');
  fs.writeFileSync(storePath, storeCanvas.toBuffer('image/png'));
  console.log(`[OK] ic_launcher-playstore-512.png (512x512)`);

  console.log('\nAll application icons successfully updated!');
}

if (require.main === module) {
  generateAllIcons();
}

module.exports = { drawOpenTouchpadIcon, generateAllIcons };
