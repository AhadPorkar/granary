/* Ordering and counting of the dashboard's upstream health list; with 20+ formats there are dozens of proxies. */

import type { UpstreamStatus } from '@/api/types';

export type UpstreamState = 'unreachable' | 'offline' | 'reachable';

/** Offline wins over reachability: an offline proxy does not contact its upstream at all. */
export function upstreamState(upstream: UpstreamStatus, globalOffline: boolean): UpstreamState {
  if (upstream.offlineMode || globalOffline) {
    return 'offline';
  }
  return upstream.status.reachable ? 'reachable' : 'unreachable';
}

const RANK: Record<UpstreamState, number> = { unreachable: 0, offline: 1, reachable: 2 };

/**
 * Problems first: unreachable upstreams, then offline ones, then the healthy rest; by repository name
 * within each state. The input is not modified.
 */
export function sortUpstreams(upstreams: readonly UpstreamStatus[], globalOffline: boolean): UpstreamStatus[] {
  return [...upstreams].sort(
    (a, b) =>
      RANK[upstreamState(a, globalOffline)] - RANK[upstreamState(b, globalOffline)] ||
      a.repository.localeCompare(b.repository, undefined, { sensitivity: 'base' }) ||
      a.repository.localeCompare(b.repository),
  );
}

export function countUpstreams(upstreams: readonly UpstreamStatus[], globalOffline: boolean): Record<UpstreamState, number> {
  const counts: Record<UpstreamState, number> = { unreachable: 0, offline: 0, reachable: 0 };
  for (const upstream of upstreams) {
    counts[upstreamState(upstream, globalOffline)]++;
  }
  return counts;
}

/**
 * How many entries the collapsed list shows: at least `limit`, and never fewer than the unreachable
 * upstreams, so a failure is never hidden behind "show all". Offline proxies are a deliberate choice
 * (and with global offline mode every proxy is offline), so they do not force the list open.
 */
export function collapsedCount(upstreams: readonly UpstreamStatus[], globalOffline: boolean, limit: number): number {
  const attention = upstreams.filter((u) => upstreamState(u, globalOffline) === 'unreachable').length;
  return Math.min(upstreams.length, Math.max(limit, attention));
}
