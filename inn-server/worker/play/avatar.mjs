// SPDX-License-Identifier: GPL-3.0-or-later
import { AvatarCatalog, normalizeAppearance } from './avatar-catalog.mjs';

const { frameWidth, frameHeight, frames, hairColors, outfitColors, hairStyles, accessories } = AvatarCatalog;
const jobImages = new Map();
const avatars = new Map();
const CACHE_LIMIT = 128;

export function getAvatar(value) {
  const appearance = normalizeAppearance(value);
  const { job, outfitColor, hairStyle, hairColor, accessory } = appearance;
  const key = [job, outfitColor, hairStyle, hairColor, accessory].join('/');
  const existing = avatars.get(key);
  if (existing) {
    avatars.delete(key);
    avatars.set(key, existing);
    return existing;
  }

  let image = jobImages.get(job);
  if (!image) {
    image = new Image();
    image.src = `/play/assets/avatars/${job}.png`;
    jobImages.set(job, image);
  }

  const outfit = outfitColors.findIndex(({ id }) => id === outfitColor);
  const style = hairStyles.findIndex(({ id }) => id === hairStyle);
  const color = hairColors.findIndex(({ id }) => id === hairColor);
  const extra = accessories.findIndex(({ id }) => id === accessory);
  const rows = [0, 1 + outfit, 1 + outfitColors.length + style * hairColors.length + color,
    1 + outfitColors.length + hairStyles.length * hairColors.length + extra];
  const avatar = { image, rows, sheet: null };
  avatars.set(key, avatar);
  if (avatars.size > CACHE_LIMIT) avatars.delete(avatars.keys().next().value);
  return avatar;
}

export function drawAvatar(context, avatar, frame, x, y, width, height) {
  if (!avatar) return false;
  if (!avatar.sheet) {
    const stripWidth = frameWidth * frames;
    if (avatar.image.naturalWidth !== stripWidth ||
        avatar.image.naturalHeight < (Math.max(...avatar.rows) + 1) * frameHeight) return false;

    const sheet = document.createElement('canvas');
    sheet.width = stripWidth;
    sheet.height = frameHeight;
    const composite = sheet.getContext('2d');
    for (const row of avatar.rows) {
      composite.drawImage(avatar.image, 0, row * frameHeight, stripWidth, frameHeight, 0, 0, stripWidth, frameHeight);
    }
    avatar.sheet = sheet;
  }
  const column = Math.min(frames - 1, Math.max(0, frame));
  context.drawImage(avatar.sheet, column * frameWidth, 0, frameWidth, frameHeight, x, y, width, height);
  return true;
}
