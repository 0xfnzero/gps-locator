import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const root = dirname(fileURLToPath(import.meta.url));
const limits = {
  "title.txt": 30,
  "short_description.txt": 80,
  "full_description.txt": 4000,
  "changelog-4.txt": 500
};

const locales = ["en-US", "zh-CN", "ja-JP", "es-ES"];
const screenshotNames = [
  "01-map-selection.png",
  "02-region-search.png",
  "03-saved-locations.png",
  "04-route-editor.png",
];

for (const locale of locales) {
  for (const [name, limit] of Object.entries(limits)) {
    const value = readFileSync(join(root, locale, name), "utf8").trim();
    const length = [...value].length;
    assert.ok(value, `${locale}/${name} must not be empty`);
    assert.ok(length <= limit, `${locale}/${name} is ${length} characters; limit is ${limit}`);
    console.log(`${locale}/${name}: ${length}/${limit}`);
  }
}

function paeth(left, up, upLeft) {
  const estimate = left + up - upLeft;
  const leftDistance = Math.abs(estimate - left);
  const upDistance = Math.abs(estimate - up);
  const upLeftDistance = Math.abs(estimate - upLeft);
  if (leftDistance <= upDistance && leftDistance <= upLeftDistance) return left;
  return upDistance <= upLeftDistance ? up : upLeft;
}

function validatePng(path, expectedWidth, expectedHeight, requireOpaqueRgb = false) {
  const image = readFileSync(path);
  assert.ok(image.length >= 180_000, `${path} is unexpectedly small (${image.length} bytes)`);
  assert.ok(image.length <= 8 * 1024 * 1024, `${path} exceeds the Play 8 MB image limit`);
  assert.equal(image.subarray(0, 8).toString("hex"), "89504e470d0a1a0a", `${path} is not PNG`);

  let offset = 8;
  let width;
  let height;
  let bitDepth;
  let colorType;
  let interlace;
  const compressed = [];
  while (offset < image.length) {
    const length = image.readUInt32BE(offset);
    const type = image.subarray(offset + 4, offset + 8).toString("ascii");
    const data = image.subarray(offset + 8, offset + 8 + length);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === "IDAT") {
      compressed.push(data);
    }
    offset += length + 12;
    if (type === "IEND") break;
  }

  assert.equal(width, expectedWidth, `${path} width must be ${expectedWidth}`);
  assert.equal(height, expectedHeight, `${path} height must be ${expectedHeight}`);
  assert.equal(bitDepth, 8, `${path} must use 8-bit PNG channels`);
  assert.ok(colorType === 2 || colorType === 6, `${path} must use RGB or RGBA PNG`);
  assert.equal(interlace, 0, `${path} must be non-interlaced PNG`);
  if (requireOpaqueRgb) assert.equal(colorType, 2, `${path} must not have an alpha channel`);

  const channels = colorType === 6 ? 4 : 3;
  const bytesPerPixel = channels;
  const stride = width * channels;
  const inflated = inflateSync(Buffer.concat(compressed));
  assert.equal(inflated.length, (stride + 1) * height, `${path} has unexpected pixel data`);
  let previous = Buffer.alloc(stride);
  let inputOffset = 0;
  const colors = new Set();
  const mapColors = new Set();
  let opaque = true;

  for (let y = 0; y < height; y += 1) {
    const filter = inflated[inputOffset];
    inputOffset += 1;
    const row = Buffer.allocUnsafe(stride);
    for (let byte = 0; byte < stride; byte += 1) {
      const raw = inflated[inputOffset + byte];
      const left = byte >= bytesPerPixel ? row[byte - bytesPerPixel] : 0;
      const up = previous[byte];
      const upLeft = byte >= bytesPerPixel ? previous[byte - bytesPerPixel] : 0;
      const predictor = filter === 0 ? 0
        : filter === 1 ? left
          : filter === 2 ? up
            : filter === 3 ? Math.floor((left + up) / 2)
              : filter === 4 ? paeth(left, up, upLeft)
                : Number.NaN;
      assert.ok(Number.isFinite(predictor), `${path} uses unsupported PNG filter ${filter}`);
      row[byte] = (raw + predictor) & 0xff;
    }
    inputOffset += stride;

    for (let x = 0; x < width; x += 8) {
      const pixel = x * channels;
      const color = (row[pixel] << 16) | (row[pixel + 1] << 8) | row[pixel + 2];
      if (y % 8 === 0) colors.add(color);
      if (y >= 320 && y < Math.min(1_220, height - 500) && y % 8 === 0) {
        mapColors.add(color);
      }
      if (colorType === 6 && row[pixel + 3] !== 255) opaque = false;
    }
    previous = row;
  }

  assert.ok(colors.size >= 100, `${path} has too little visual detail (${colors.size} colors)`);
  assert.ok(opaque, `${path} contains transparent sampled pixels`);
  return { width, height, colors: colors.size, mapColors: mapColors.size, bytes: image.length };
}

const phoneRoot = join(root, "assets", "phone");
for (const locale of locales) {
  const localeRoot = join(phoneRoot, locale);
  const actualFiles = readdirSync(localeRoot).filter((name) => name.endsWith(".png")).sort();
  assert.deepEqual(actualFiles, screenshotNames, `${locale} phone screenshot set is incomplete`);
  for (const name of screenshotNames) {
    const details = validatePng(join(localeRoot, name), 1080, 1920);
    if (name === "01-map-selection.png") {
      assert.ok(
        details.mapColors >= 200,
        `${locale}/${name} map looks blank (${details.mapColors} sampled colors)`,
      );
    }
    console.log(
      `${locale}/${name}: ${details.width}x${details.height}, ` +
      `${details.colors} sampled colors, ${details.bytes} bytes`,
    );
  }
}

const feature = validatePng(join(root, "assets", "feature-graphic.png"), 1024, 500, true);
console.log(
  `feature-graphic.png: ${feature.width}x${feature.height}, ` +
  `${feature.colors} sampled colors, ${feature.bytes} bytes`,
);
