import { describe, expect, it } from 'vitest';
import { formatBytes, formatDateTime, formatDuration, formatNumber, formatRelative, fromDateTimeLocal, toDateTimeLocal } from '@/utils/format';

describe('formatting through Intl', () => {
  it('formats byte sizes with locale-specific separators and units', () => {
    expect(formatBytes(0, 'en')).toMatch(/^0\s?byte/);
    expect(formatBytes(1536, 'en')).toBe('1.5 kB');
    expect(formatBytes(1536, 'de')).toBe('1,5 kB');
    expect(formatBytes(5 * 1024 * 1024 * 1024, 'en')).toBe('5 GB');
    expect(formatBytes(null, 'en')).toBe('');
  });

  it('formats numbers with the digits of the locale', () => {
    expect(formatNumber(1234567, 'en')).toBe('1,234,567');
    expect(formatNumber(1234567, 'de')).toBe('1.234.567');
    expect(formatNumber(42, 'fa')).toBe('۴۲');
  });

  it('formats durations in the unit that keeps the number short', () => {
    expect(formatDuration(0, 'en')).toBe('0 ms');
    expect(formatDuration(850, 'en')).toBe('850 ms');
    expect(formatDuration(4200, 'en')).toBe('4.2 sec');
    expect(formatDuration(4200, 'de')).toBe('4,2 Sek.');
    expect(formatDuration(210_000, 'en')).toBe('3.5 min');
    expect(formatDuration(2 * 3_600_000, 'en')).toBe('2 hr');
    expect(formatDuration(undefined, 'en')).toBe('');
  });

  it('formats dates and relative times, tolerating missing values', () => {
    expect(formatDateTime(null, 'en')).toBe('');
    expect(formatDateTime('2026-03-04T05:06:00Z', 'en')).toContain('2026');
    const now = Date.parse('2026-03-04T12:00:00Z');
    expect(formatRelative('2026-03-04T11:00:00Z', 'en', now)).toBe('1 hour ago');
    expect(formatRelative('2026-03-06T12:00:00Z', 'en', now)).toBe('in 2 days');
  });

  it('round-trips datetime-local input values', () => {
    expect(fromDateTimeLocal('')).toBeNull();
    const iso = fromDateTimeLocal('2030-01-02T03:04');
    expect(iso).toBeTruthy();
    expect(toDateTimeLocal(iso)).toBe('2030-01-02T03:04');
  });
});
