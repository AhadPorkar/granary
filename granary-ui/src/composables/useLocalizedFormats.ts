import { computed, type Ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormatView } from '@/api/types';
import { localizeFormats } from '@/utils/formatText';

/** The formats as the server lists them, with display names and setting texts in the UI language. */
export function useLocalizedFormats(formats: Ref<FormatView[]>) {
  const { t, te, locale } = useI18n();
  return computed(() =>
    localizeFormats(formats.value, { locale: locale.value, t: (key) => t(key), te: (key, l) => te(key, l) }),
  );
}
