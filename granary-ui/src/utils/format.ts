/* Locale-aware formatting through Intl; nothing here is translated by hand. */

const BYTE_UNITS = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte', 'petabyte'] as const;
const BYTE_ABBREVIATIONS = ['B', 'kB', 'MB', 'GB', 'TB', 'PB'] as const;

export function formatNumber(value: number | null | undefined, locale: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '';
  }
  try {
    return new Intl.NumberFormat(locale).format(value);
  } catch {
    return String(value);
  }
}

/**
 * Base languages written right to left. Their short byte units are Latin ("kB"), and a Latin unit next to
 * the locale's own digits forms a left-to-right run that the bidi algorithm reorders inside a sentence
 * ("kB ۰۴ ۱۲ مؤلفه"), so these locales get the spelled-out unit ("۱۲ کیلوبایت").
 */
const RTL_LANGUAGES = new Set(['ar', 'fa', 'he', 'ur']);

function spellsOutUnits(locale: string): boolean {
  return RTL_LANGUAGES.has(locale.toLowerCase().split('-')[0]);
}

export function formatBytes(bytes: number | null | undefined, locale: string): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return '';
  }
  let value = Math.max(0, bytes);
  let index = 0;
  while (value >= 1024 && index < BYTE_UNITS.length - 1) {
    value /= 1024;
    index += 1;
  }
  const digits = index === 0 ? 0 : value < 10 ? 1 : 0;
  try {
    return new Intl.NumberFormat(locale, {
      style: 'unit',
      unit: BYTE_UNITS[index],
      // plain bytes are spelled out: the short form reads "0 byte" in English
      unitDisplay: index === 0 || spellsOutUnits(locale) ? 'long' : 'short',
      maximumFractionDigits: digits,
    }).format(value);
  } catch {
    return `${value.toFixed(digits)} ${BYTE_ABBREVIATIONS[index]}`;
  }
}

export function formatPercent(fraction: number, locale: string): string {
  try {
    return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 }).format(fraction);
  } catch {
    return `${Math.round(fraction * 100)}%`;
  }
}

const DURATION_STEPS: Array<[string, number]> = [
  ['hour', 3_600_000],
  ['minute', 60_000],
  ['second', 1000],
  ['millisecond', 1],
];

/** A duration in milliseconds as "850 ms", "4.2 sec" or "3.5 min", in the unit that keeps the number short. */
export function formatDuration(millis: number | null | undefined, locale: string): string {
  if (millis === null || millis === undefined || Number.isNaN(millis)) {
    return '';
  }
  const ms = Math.max(0, millis);
  const [unit, size] = DURATION_STEPS.find(([, stepSize]) => ms >= stepSize) ?? ['millisecond', 1];
  const value = ms / size;
  const digits = unit === 'millisecond' ? 0 : 1;
  try {
    return new Intl.NumberFormat(locale, {
      style: 'unit',
      unit,
      unitDisplay: 'short',
      maximumFractionDigits: digits,
    }).format(value);
  } catch {
    return `${value.toFixed(digits)} ${unit}`;
  }
}

export function formatDateTime(iso: string | null | undefined, locale: string): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
  } catch {
    return date.toISOString();
  }
}

export function formatDate(iso: string | null | undefined, locale: string): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
  } catch {
    return date.toISOString().slice(0, 10);
  }
}

const RELATIVE_STEPS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
  ['second', 1],
];

export function formatRelative(iso: string | null | undefined, locale: string, now: number = Date.now()): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const seconds = Math.round((date.getTime() - now) / 1000);
  const abs = Math.abs(seconds);
  let unit: Intl.RelativeTimeFormatUnit = 'second';
  let amount = seconds;
  for (const [candidate, size] of RELATIVE_STEPS) {
    if (abs >= size) {
      unit = candidate;
      amount = Math.round(seconds / size);
      break;
    }
  }
  try {
    return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(amount, unit);
  } catch {
    return formatDateTime(iso, locale);
  }
}

/** Converts an ISO instant to the value of an <input type="datetime-local"> in the browser's zone. */
export function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The reverse of {@link toDateTimeLocal}; an empty input becomes null. */
export function fromDateTimeLocal(value: string): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** "Hosted and Proxy" / "Hosted, Proxy und Group": a list joined the way the locale joins lists. */
export function formatList(items: readonly string[], locale: string): string {
  try {
    return new Intl.ListFormat(locale, { type: 'conjunction' }).format(items);
  } catch {
    return items.join(', ');
  }
}

/** Splits a path after each "/" so a template can offer a line break there (paths have no spaces). */
export function pathSegments(path: string): string[] {
  return path.match(/[^/]*\/|[^/]+$/g) ?? [path];
}
