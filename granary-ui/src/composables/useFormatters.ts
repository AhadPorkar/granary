import { useLocaleStore } from '@/stores/locale';
import { formatBytes, formatDate, formatDateTime, formatDuration, formatList, formatNumber, formatPercent, formatRelative } from '@/utils/format';

/** Formatting helpers bound to the current UI locale. */
export function useFormatters() {
  const locale = useLocaleStore();
  return {
    number: (value: number | null | undefined) => formatNumber(value, locale.intlTag),
    bytes: (value: number | null | undefined) => formatBytes(value, locale.intlTag),
    percent: (fraction: number) => formatPercent(fraction, locale.intlTag),
    duration: (millis: number | null | undefined) => formatDuration(millis, locale.intlTag),
    dateTime: (iso: string | null | undefined) => formatDateTime(iso, locale.intlTag),
    date: (iso: string | null | undefined) => formatDate(iso, locale.intlTag),
    relative: (iso: string | null | undefined) => formatRelative(iso, locale.intlTag),
    list: (items: readonly string[]) => formatList(items, locale.intlTag),
  };
}
