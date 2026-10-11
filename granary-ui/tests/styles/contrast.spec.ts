import { readFileSync } from 'node:fs';
import { fileURLToPath, URL as NodeURL } from 'node:url';
import { describe, expect, it } from 'vitest';

// Vitest does not load stylesheets, so the file is read directly (jsdom replaces the global URL).
const css = readFileSync(fileURLToPath(new NodeURL('../../src/styles/main.css', import.meta.url)), 'utf8');

/*
 * The colour tokens of both themes must meet WCAG 2.2 AA: 4.5:1 for text on the backgrounds it is
 * used on, 3:1 for the borders that outline inputs, checkboxes and switches.
 */

function tokens(source: string): Record<string, string> {
  const found: Record<string, string> = {};
  for (const match of source.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\b/g)) {
    found[match[1]!] = match[2]!.toLowerCase();
  }
  return found;
}

const darkStart = css.indexOf('@media (prefers-color-scheme: dark)');
const light = tokens(css.slice(css.indexOf(':root {'), darkStart));
const dark = { ...light, ...tokens(css.slice(darkStart, css.indexOf('}', css.indexOf(':root {', darkStart)))) };

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

function contrast(a: string, b: string): number {
  const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (high! + 0.05) / (low! + 0.05);
}

const TEXT_PAIRS: [string, string][] = [
  ['text', 'bg'],
  ['text', 'surface'],
  ['text-muted', 'bg'],
  ['text-muted', 'surface'],
  ['text-muted', 'surface-2'],
  ['text-muted', 'surface-3'],
  ['text-faint', 'bg'],
  ['text-faint', 'surface'],
  ['text-faint', 'surface-2'],
  ['primary', 'bg'],
  ['primary', 'surface'],
  ['primary', 'primary-soft'],
  ['primary-contrast', 'primary'],
  ['danger', 'surface'],
  ['danger', 'danger-soft'],
  ['success', 'success-soft'],
  ['warning', 'warning-soft'],
  ['info', 'info-soft'],
];

describe.each([
  ['light', light],
  ['dark', dark],
])('%s theme colours', (_theme, palette) => {
  it('defines every token the checks need', () => {
    for (const name of [...TEXT_PAIRS.flat(), 'border-strong', 'focus']) {
      expect(palette[name], name).toMatch(/^#[0-9a-f]{6}$/);
    }
  });

  it.each(TEXT_PAIRS)('%s on %s reaches 4.5:1', (fg, bg) => {
    expect(contrast(palette[fg]!, palette[bg]!)).toBeGreaterThanOrEqual(4.5);
  });

  it('outlines controls and focus rings at 3:1 or more', () => {
    for (const bg of ['surface', 'bg']) {
      expect(contrast(palette['border-strong']!, palette[bg]!), `border-strong on ${bg}`).toBeGreaterThanOrEqual(3);
      expect(contrast(palette.focus!, palette[bg]!), `focus on ${bg}`).toBeGreaterThanOrEqual(3);
    }
  });
});

it('reads both palettes from the stylesheet', () => {
  expect(light.text).not.toBe(dark.text);
  expect(Object.keys(dark).length).toBeGreaterThanOrEqual(Object.keys(light).length);
});
