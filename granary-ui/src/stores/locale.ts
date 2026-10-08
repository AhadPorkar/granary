import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import {
  INTL_TAGS,
  LOCALE_NAMES,
  SUPPORTED_LOCALES,
  applyDocumentLocale,
  i18n,
  isRtl,
  isSupportedLocale,
  saveLocale,
  type Locale,
} from '@/i18n';

export const useLocaleStore = defineStore('locale', () => {
  const current = ref<Locale>(i18n.global.locale.value as Locale);

  const rtl = computed(() => isRtl(current.value));
  const dir = computed<'ltr' | 'rtl'>(() => (rtl.value ? 'rtl' : 'ltr'));
  const intlTag = computed(() => INTL_TAGS[current.value]);
  const options = computed(() => SUPPORTED_LOCALES.map((code) => ({ code, name: LOCALE_NAMES[code] })));

  function set(locale: Locale): void {
    if (!isSupportedLocale(locale)) {
      return;
    }
    current.value = locale;
    i18n.global.locale.value = locale;
    saveLocale(locale);
    applyDocumentLocale(locale);
  }

  /** Call once at start-up so <html lang/dir> matches the detected locale. */
  function init(): void {
    applyDocumentLocale(current.value);
  }

  return { current, rtl, dir, intlTag, options, set, init };
});
