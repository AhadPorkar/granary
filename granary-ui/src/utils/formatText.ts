/*
 * Format display names and format setting labels come from the server, in English. The UI keeps
 * translations of them under `formatText` in its locale files: `formatText.formats.<format id>` for
 * display names that contain ordinary words, `formatText.settings.<setting key>.label|description`
 * for settings. English always shows the server's own text (it is the source the translations were
 * made from); another locale shows its translation when it has one and the server's text otherwise,
 * so a format or setting added later still appears, just untranslated until the catalogue catches up.
 */

import type { FormatView, SettingDescriptor } from '@/api/types';

export const SOURCE_LOCALE = 'en';

export interface Translator {
  locale: string;
  t: (key: string) => string;
  /** Whether `key` exists in the messages of `locale` itself, without fallback. */
  te: (key: string, locale: string) => boolean;
}

const SEGMENT = /^[A-Za-z0-9_-]+$/;

/** Message path for a format id or setting key, or null when it cannot be one (dots only as separators). */
function path(prefix: string, id: string, suffix?: string): string | null {
  const parts = id.split('.');
  if (!parts.every((part) => SEGMENT.test(part))) {
    return null;
  }
  return [prefix, ...parts, ...(suffix ? [suffix] : [])].join('.');
}

export function formatNameKey(format: string): string | null {
  return path('formatText.formats', format);
}

export function settingTextKey(setting: string, part: 'label' | 'description'): string | null {
  return path('formatText.settings', setting, part);
}

function translate(key: string | null, fallback: string, tr: Translator): string {
  if (tr.locale === SOURCE_LOCALE || !key || !tr.te(key, tr.locale)) {
    return fallback;
  }
  return tr.t(key);
}

export function localizeSetting(setting: SettingDescriptor, tr: Translator): SettingDescriptor {
  const description = setting.description ?? null;
  return {
    ...setting,
    label: translate(settingTextKey(setting.key, 'label'), setting.label, tr),
    description: description === null ? null : translate(settingTextKey(setting.key, 'description'), description, tr),
  };
}

export function localizeFormat(format: FormatView, tr: Translator): FormatView {
  return {
    ...format,
    displayName: translate(formatNameKey(format.id), format.displayName, tr),
    settings: (format.settings ?? []).map((s) => localizeSetting(s, tr)),
  };
}

export function localizeFormats(formats: readonly FormatView[], tr: Translator): FormatView[] {
  return formats.map((f) => localizeFormat(f, tr));
}
