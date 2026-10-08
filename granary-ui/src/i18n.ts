import { createI18n } from 'vue-i18n';
import en from './locales/en.json';
import de from './locales/de.json';
import fr from './locales/fr.json';
import tr from './locales/tr.json';
import fa from './locales/fa.json';

export const SUPPORTED_LOCALES = ['en', 'de', 'fr', 'tr', 'fa'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: Locale = 'en';
export const RTL_LOCALES: readonly Locale[] = ['fa'];
export const LOCALE_STORAGE_KEY = 'granary.locale';

/** Native names shown in the language switcher; they are never translated. */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: 'English',
  de: 'Deutsch',
  fr: 'Français',
  tr: 'Türkçe',
  fa: 'فارسی',
};

/** BCP 47 tags handed to Intl so numbers and dates follow each locale's conventions. */
export const INTL_TAGS: Record<Locale, string> = {
  en: 'en',
  de: 'de',
  fr: 'fr',
  tr: 'tr',
  fa: 'fa',
};

export type MessageSchema = typeof en;

export const messages: Record<Locale, MessageSchema> = { en, de, fr, tr, fa };

export function isSupportedLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

export function isRtl(locale: Locale): boolean {
  return RTL_LOCALES.includes(locale);
}

/**
 * The saved choice wins; otherwise the first browser language whose base tag we support; otherwise English.
 */
export function detectLocale(saved: string | null | undefined, browserLanguages: readonly string[] = []): Locale {
  if (isSupportedLocale(saved)) {
    return saved;
  }
  for (const language of browserLanguages) {
    const base = language.toLowerCase().split('-')[0];
    if (isSupportedLocale(base)) {
      return base;
    }
  }
  return DEFAULT_LOCALE;
}

export function readSavedLocale(): string | null {
  try {
    return globalThis.localStorage?.getItem(LOCALE_STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

export function saveLocale(locale: Locale): void {
  try {
    globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    /* ignore: private mode or disabled storage */
  }
}

export function initialLocale(): Locale {
  const languages = typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language];
  return detectLocale(readSavedLocale(), languages);
}

export function createAppI18n(locale: Locale = initialLocale()) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: DEFAULT_LOCALE,
    messages,
    missingWarn: false,
    fallbackWarn: false,
    warnHtmlMessage: false,
  });
}

export const i18n = createAppI18n();

/** Mirrors the locale onto <html> so CSS logical properties and the browser's bidi algorithm follow. */
export function applyDocumentLocale(locale: Locale, doc: Document | undefined = globalThis.document): void {
  if (!doc) {
    return;
  }
  doc.documentElement.setAttribute('lang', locale);
  doc.documentElement.setAttribute('dir', isRtl(locale) ? 'rtl' : 'ltr');
}
