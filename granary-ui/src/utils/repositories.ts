/* Pure filtering and grouping of the repository list; the view only renders what comes out of here. */

import type { ComponentView, FormatView, RepositoryView } from '@/api/types';

export interface RepositoryFilter {
  /** Case-insensitive substring of the name or description; blank matches everything. */
  query?: string;
  /** Format id; blank or undefined matches every format. */
  format?: string;
}

export interface RepositoryGroup {
  format: string;
  displayName: string;
  repositories: RepositoryView[];
}

/** Display name of a format, falling back to its id for formats the server no longer lists. */
export function formatDisplayName(format: string, formats: readonly FormatView[]): string {
  return formats.find((f) => f.id === format)?.displayName ?? format;
}

export function matchesQuery(repository: RepositoryView, query: string | undefined): boolean {
  const q = (query ?? '').trim().toLocaleLowerCase();
  if (!q) {
    return true;
  }
  return repository.name.toLocaleLowerCase().includes(q) || (repository.description ?? '').toLocaleLowerCase().includes(q);
}

export function filterRepositories(repositories: readonly RepositoryView[], filter: RepositoryFilter): RepositoryView[] {
  const format = (filter.format ?? '').trim();
  return repositories.filter((r) => (!format || r.format === format) && matchesQuery(r, filter.query));
}

/**
 * Groups repositories by format, groups ordered by display name (then id for ties), repositories
 * inside a group in their incoming order. Formats without repositories do not appear.
 */
export function groupByFormat(repositories: readonly RepositoryView[], formats: readonly FormatView[]): RepositoryGroup[] {
  const groups = new Map<string, RepositoryGroup>();
  for (const repository of repositories) {
    let group = groups.get(repository.format);
    if (!group) {
      group = { format: repository.format, displayName: formatDisplayName(repository.format, formats), repositories: [] };
      groups.set(repository.format, group);
    }
    group.repositories.push(repository);
  }
  return [...groups.values()].sort(
    (a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }) || a.format.localeCompare(b.format),
  );
}

/** Number of repositories per format, in the same order as {@link groupByFormat}. */
export function countByFormat(repositories: readonly RepositoryView[], formats: readonly FormatView[]): Array<{ format: string; displayName: string; count: number }> {
  return groupByFormat(repositories, formats).map((g) => ({ format: g.format, displayName: g.displayName, count: g.repositories.length }));
}

/** The format ids that occur in the list, as select options sorted by display name. */
export function formatOptions(repositories: readonly RepositoryView[], formats: readonly FormatView[]): Array<{ id: string; displayName: string }> {
  const ids = new Set<string>(formats.map((f) => f.id));
  for (const r of repositories) {
    ids.add(r.format);
  }
  return [...ids]
    .map((id) => ({ id, displayName: formatDisplayName(id, formats) }))
    .sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }) || a.id.localeCompare(b.id));
}

/** Whether a namespace column is worth showing: many formats (raw, PyPI, Cargo, ...) never set one. */
export function anyNamespace(components: readonly ComponentView[]): boolean {
  return components.some((c) => (c.namespace ?? '').trim() !== '');
}
