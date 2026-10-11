import { describe, expect, it } from 'vitest';
import { SUPPORTED_LOCALES, messages, type Locale } from '@/i18n';
import { flatten } from './helpers';

const en = flatten(messages.en);
const OTHER_LOCALES = SUPPORTED_LOCALES.filter((l) => l !== 'en') as Locale[];

function placeholders(text: string): string[] {
  return (text.match(/\{[a-zA-Z0-9_]+\}/g) ?? []).sort();
}

describe('locale files', () => {
  it('English defines a non-trivial message catalogue', () => {
    expect(Object.keys(en).length).toBeGreaterThan(300);
  });

  it.each(OTHER_LOCALES)('%s has exactly the same keys as en', (locale) => {
    const keys = Object.keys(flatten(messages[locale])).sort();
    expect(keys).toEqual(Object.keys(en).sort());
  });

  it.each(SUPPORTED_LOCALES)('%s has no empty or whitespace-only strings', (locale) => {
    const empty = Object.entries(flatten(messages[locale]))
      .filter(([, value]) => value.trim() === '')
      .map(([key]) => key);
    expect(empty).toEqual([]);
  });

  it.each(OTHER_LOCALES)('%s keeps the interpolation placeholders of en', (locale) => {
    const flat = flatten(messages[locale]);
    const mismatches = Object.keys(en).filter((key) => flat[key] !== undefined && placeholders(flat[key]).join() !== placeholders(en[key]).join());
    expect(mismatches).toEqual([]);
  });

  it.each(OTHER_LOCALES)('%s is actually translated, not a copy of en', (locale) => {
    const flat = flatten(messages[locale]);
    const identical = Object.keys(en).filter((key) => flat[key] === en[key]);
    // Product names, codes and a few short technical labels legitimately stay the same.
    expect(identical.length / Object.keys(en).length).toBeLessThan(0.15);
  });

  it.each(SUPPORTED_LOCALES)('%s uses no unescaped vue-i18n special characters', (locale) => {
    const offenders = Object.entries(flatten(messages[locale]))
      .filter(([, value]) => /(^|[^{'])[@|]/.test(value))
      .map(([key]) => key);
    expect(offenders).toEqual([]);
  });
});

describe('Persian messages', () => {
  const LRI = '\u2066';
  const PDI = '\u2069';

  it('isolate left-to-right paths, commands and quoted values so their punctuation stays in place', () => {
    const offenders = Object.entries(flatten(messages.fa))
      .map(([key, value]) => [key, value.replace(new RegExp(`${LRI}[^${PDI}]*${PDI}`, 'g'), '').replace(/\{[A-Za-z0-9_]+\}/g, '')] as const)
      .filter(([, rest]) => /[\u0600-\u06ff]/.test(rest))
      .filter(([, rest]) => /[A-Za-z0-9][/:][A-Za-z<]|<[^<>\s]+>|"[A-Za-z][^"]*"|\{'\{'\}|(^|\s)[.-]{1,2}[A-Za-z]/.test(rest))
      .map(([key]) => key);
    expect(offenders).toEqual([]);
  });

  it('balances every isolate it opens', () => {
    for (const [key, value] of Object.entries(flatten(messages.fa))) {
      expect(value.split(LRI).length, key).toBe(value.split(PDI).length);
    }
  });
});
