import { describe, expect, it } from 'vitest';
import type { UpstreamStatus } from '@/api/types';
import { collapsedCount, countUpstreams, sortUpstreams, upstreamState } from '@/utils/upstreams';

function upstream(repository: string, reachable: boolean, offlineMode = false): UpstreamStatus {
  return {
    repository,
    remoteUrl: `https://example.org/${repository}/`,
    offlineMode,
    status: { reachable, consecutiveFailures: reachable ? 0 : 3 },
  };
}

const mixed = [
  upstream('pypi-proxy', true),
  upstream('docker-hub', false),
  upstream('maven-central', true, true),
  upstream('crates-io', true),
  upstream('Cargo-mirror', false),
];

describe('upstream health list', () => {
  it('treats offline mode as stronger than reachability', () => {
    expect(upstreamState(upstream('a', true), false)).toBe('reachable');
    expect(upstreamState(upstream('a', false), false)).toBe('unreachable');
    expect(upstreamState(upstream('a', false, true), false)).toBe('offline');
    expect(upstreamState(upstream('a', false), true)).toBe('offline');
  });

  it('puts unreachable upstreams first, then offline ones, by name within a state', () => {
    expect(sortUpstreams(mixed, false).map((u) => u.repository)).toEqual(['Cargo-mirror', 'docker-hub', 'maven-central', 'crates-io', 'pypi-proxy']);
    // the input keeps its order
    expect(mixed[0].repository).toBe('pypi-proxy');
  });

  it('orders by name alone when global offline mode makes every proxy offline', () => {
    expect(sortUpstreams(mixed, true).map((u) => u.repository)).toEqual(['Cargo-mirror', 'crates-io', 'docker-hub', 'maven-central', 'pypi-proxy']);
  });

  it('counts the upstreams per state', () => {
    expect(countUpstreams(mixed, false)).toEqual({ unreachable: 2, offline: 1, reachable: 2 });
    expect(countUpstreams(mixed, true)).toEqual({ unreachable: 0, offline: 5, reachable: 0 });
    expect(countUpstreams([], false)).toEqual({ unreachable: 0, offline: 0, reachable: 0 });
  });

  it('collapses to the limit but never hides an unreachable upstream', () => {
    const many = Array.from({ length: 30 }, (_, i) => upstream(`proxy-${String(i).padStart(2, '0')}`, i % 3 !== 0));
    expect(collapsedCount(many, false, 8)).toBe(10);
    expect(collapsedCount(many, false, 12)).toBe(12);
    expect(collapsedCount(mixed, false, 8)).toBe(5);
    // offline proxies are a choice, not a failure: global offline mode does not open the list
    expect(collapsedCount(many, true, 8)).toBe(8);
  });
});
