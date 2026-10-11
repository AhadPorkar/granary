import { describe, expect, it } from 'vitest';
import { LOCALE_NAMES, LOCALE_STORAGE_KEY, applyDocumentLocale, detectLocale, i18n, isRtl } from '@/i18n';
import { useLocaleStore } from '@/stores/locale';
import { freshPinia } from './helpers';

describe('detectLocale', () => {
  it('prefers the saved choice', () => {
    expect(detectLocale('tr', ['de-DE'])).toBe('tr');
  });

  it('falls back to the first supported browser language, ignoring the region', () => {
    expect(detectLocale(null, ['pt-BR', 'de-AT', 'en'])).toBe('de');
    expect(detectLocale(undefined, ['fa-IR'])).toBe('fa');
  });

  it('defaults to English when nothing matches', () => {
    expect(detectLocale('xx', ['pt-BR', 'ja'])).toBe('en');
    expect(detectLocale(null, [])).toBe('en');
  });

  it('knows the five supported locales by their native names', () => {
    // fa: "Farsi" in Persian script
    expect(LOCALE_NAMES).toEqual({ en: 'English', de: 'Deutsch', fr: 'Français', tr: 'Türkçe', fa: '\u0641\u0627\u0631\u0633\u06cc' });
  });
});

describe('text direction', () => {
  it('only Persian is right-to-left', () => {
    expect(isRtl('fa')).toBe(true);
    expect(isRtl('en')).toBe(false);
    expect(isRtl('tr')).toBe(false);
  });

  it('applyDocumentLocale mirrors lang and dir onto <html>', () => {
    applyDocumentLocale('fa');
    expect(document.documentElement.getAttribute('dir')).toBe('rtl');
    expect(document.documentElement.getAttribute('lang')).toBe('fa');
    applyDocumentLocale('fr');
    expect(document.documentElement.getAttribute('dir')).toBe('ltr');
    expect(document.documentElement.getAttribute('lang')).toBe('fr');
  });
});

describe('locale store', () => {
  it('switching to fa flips the document to RTL, changes translations and persists the choice', () => {
    freshPinia();
    const store = useLocaleStore();
    store.set('fa');
    expect(store.current).toBe('fa');
    expect(store.rtl).toBe(true);
    expect(store.dir).toBe('rtl');
    expect(i18n.global.locale.value).toBe('fa');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('fa');
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('fa');
    expect(i18n.global.t('nav.dashboard')).not.toBe('Dashboard');
  });

  it('switching back to a LTR locale restores dir="ltr"', () => {
    freshPinia();
    const store = useLocaleStore();
    store.set('fa');
    store.set('de');
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('de');
    expect(i18n.global.t('nav.dashboard')).toBe('Übersicht');
  });

  it('ignores unsupported locales', () => {
    freshPinia();
    const store = useLocaleStore();
    store.set('xx' as never);
    expect(store.current).toBe('en');
  });
});
