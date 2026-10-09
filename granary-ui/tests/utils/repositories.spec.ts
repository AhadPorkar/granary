import { describe, expect, it } from 'vitest';
import type { FormatView, RepositoryView } from '@/api/types';
import { countByFormat, filterRepositories, formatDisplayName, formatOptions, groupByFormat, matchesQuery } from '@/utils/repositories';

function repo(name: string, format: string, description: string | null = null): RepositoryView {
  return {
    id: name,
    name,
    format,
    type: 'HOSTED',
    online: true,
    blobStore: 'default',
    description,
    allowRedeploy: false,
    versionPolicy: 'MIXED',
    hasRemotePassword: false,
    hasRemoteToken: false,
    offlineMode: false,
    members: [],
    formatSettings: {},
    url: `http://localhost/repository/${name}/`,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    statistics: { assets: 0, components: 0, bytes: 0 },
  };
}

function format(id: string, displayName: string): FormatView {
  return { id, displayName, types: ['HOSTED', 'PROXY', 'GROUP'], settings: [], templates: [] };
}

const formats = [format('maven2', 'Maven'), format('npm', 'npm'), format('pypi', 'PyPI'), format('cargo', 'Cargo')];
const repositories = [
  repo('npm-proxy', 'npm', 'Proxy of the public npm registry'),
  repo('maven-central', 'maven2', 'Proxy of Maven Central'),
  repo('pypi-hosted', 'pypi'),
  repo('maven-releases', 'maven2', 'Your released artifacts'),
  repo('hex-hosted', 'hex'),
];

describe('formatDisplayName', () => {
  it('returns the display name and falls back to the id for formats the server does not list', () => {
    expect(formatDisplayName('maven2', formats)).toBe('Maven');
    expect(formatDisplayName('hex', formats)).toBe('hex');
    expect(formatDisplayName('maven2', [])).toBe('maven2');
  });
});

describe('matchesQuery', () => {
  it('matches name and description case-insensitively and ignores surrounding whitespace', () => {
    const r = repo('maven-central', 'maven2', 'Proxy of Maven Central');
    expect(matchesQuery(r, 'CENTRAL')).toBe(true);
    expect(matchesQuery(r, '  proxy of ')).toBe(true);
    expect(matchesQuery(r, 'npm')).toBe(false);
    expect(matchesQuery(r, '')).toBe(true);
    expect(matchesQuery(r, undefined)).toBe(true);
    expect(matchesQuery(repo('x', 'npm'), 'anything')).toBe(false);
  });
});

describe('filterRepositories', () => {
  it('applies the text search to name and description', () => {
    expect(filterRepositories(repositories, { query: 'maven' }).map((r) => r.name)).toEqual(['maven-central', 'maven-releases']);
    expect(filterRepositories(repositories, { query: 'proxy' }).map((r) => r.name)).toEqual(['npm-proxy', 'maven-central']);
  });

  it('restricts to one format and combines both criteria', () => {
    expect(filterRepositories(repositories, { format: 'maven2' }).map((r) => r.name)).toEqual(['maven-central', 'maven-releases']);
    expect(filterRepositories(repositories, { format: 'maven2', query: 'release' }).map((r) => r.name)).toEqual(['maven-releases']);
    expect(filterRepositories(repositories, { format: 'npm', query: 'maven' })).toEqual([]);
  });

  it('returns everything for an empty filter without changing the order', () => {
    expect(filterRepositories(repositories, {})).toEqual(repositories);
    expect(filterRepositories(repositories, { query: '  ', format: '' })).toEqual(repositories);
    expect(filterRepositories([], { query: 'x' })).toEqual([]);
  });
});

describe('groupByFormat', () => {
  it('groups by format, orders groups by display name and keeps the incoming order inside a group', () => {
    const groups = groupByFormat(repositories, formats);
    expect(groups.map((g) => [g.format, g.displayName, g.repositories.map((r) => r.name)])).toEqual([
      ['hex', 'hex', ['hex-hosted']],
      ['maven2', 'Maven', ['maven-central', 'maven-releases']],
      ['npm', 'npm', ['npm-proxy']],
      ['pypi', 'PyPI', ['pypi-hosted']],
    ]);
  });

  it('omits formats without repositories and copes with an empty list', () => {
    expect(groupByFormat([], formats)).toEqual([]);
    expect(groupByFormat([repo('a', 'npm')], formats).map((g) => g.format)).toEqual(['npm']);
  });

  it('sorts case-insensitively so npm sits between Maven and PyPI', () => {
    const names = groupByFormat(repositories, formats).map((g) => g.displayName);
    expect(names.indexOf('Maven')).toBeLessThan(names.indexOf('npm'));
    expect(names.indexOf('npm')).toBeLessThan(names.indexOf('PyPI'));
  });
});

describe('countByFormat', () => {
  it('counts repositories per format in group order', () => {
    expect(countByFormat(repositories, formats)).toEqual([
      { format: 'hex', displayName: 'hex', count: 1 },
      { format: 'maven2', displayName: 'Maven', count: 2 },
      { format: 'npm', displayName: 'npm', count: 1 },
      { format: 'pypi', displayName: 'PyPI', count: 1 },
    ]);
  });
});

describe('formatOptions', () => {
  it('lists every installed format plus formats that only exist on repositories, sorted by display name', () => {
    expect(formatOptions(repositories, formats)).toEqual([
      { id: 'cargo', displayName: 'Cargo' },
      { id: 'hex', displayName: 'hex' },
      { id: 'maven2', displayName: 'Maven' },
      { id: 'npm', displayName: 'npm' },
      { id: 'pypi', displayName: 'PyPI' },
    ]);
    expect(formatOptions([], [])).toEqual([]);
  });
});
