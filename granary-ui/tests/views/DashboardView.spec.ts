import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import DashboardView from '@/views/DashboardView.vue';
import { ApiError } from '@/api/client';
import type { CompactionReport, FormatView, RepositoryView, SystemStatus } from '@/api/types';
import { i18n } from '@/i18n';
import { useAuthStore } from '@/stores/auth';
import { useLocaleStore } from '@/stores/locale';
import { useToastStore } from '@/stores/toast';
import { freshPinia } from '../helpers';

const list = vi.fn<() => Promise<RepositoryView[]>>();
const statusCall = vi.fn<() => Promise<SystemStatus>>();
const compactCall = vi.fn<(name: string) => Promise<CompactionReport>>();

vi.mock('@/api', () => ({
  api: {
    formats: () => Promise.resolve([] as FormatView[]),
    repositories: { list: () => list() },
    system: {
      info: () => Promise.resolve(null),
      status: () => statusCall(),
      updateSettings: () => Promise.resolve({}),
    },
    blobStores: { compact: (name: string) => compactCall(name) },
  },
}));

function systemStatus(defaultBlobs: number, defaultBytes: number): SystemStatus {
  return {
    version: '1.0.0',
    offlineMode: false,
    anonymousRead: false,
    blobStores: [
      { name: 'default', blobCount: defaultBlobs, totalBytes: defaultBytes, availableBytes: 10 * 1024 * 1024 },
      { name: 'archive', blobCount: 3, totalBytes: 3000, availableBytes: 10 * 1024 * 1024 },
    ],
    upstreams: [],
    repositories: 0,
    settings: {},
    defaultAdminPassword: false,
    jvm: { version: '25', maxMemory: 1024, totalMemory: 512, freeMemory: 256, processors: 4 },
  } as unknown as SystemStatus;
}

function report(overrides: Partial<CompactionReport> = {}): CompactionReport {
  return {
    blobStore: 'default',
    startedAt: '2026-10-10T03:30:00Z',
    durationMillis: 1500,
    gracePeriod: 'PT24H',
    blobsScanned: 1200,
    blobsDeleted: 7,
    bytesReclaimed: 2048,
    ...overrides,
  };
}

/** The privileges must be in place before the view loads, so the store is prepared before mounting. */
async function mountView(privileges: string[]) {
  const pinia = freshPinia();
  useAuthStore().privileges = privileges;
  const wrapper = mount(DashboardView, {
    global: { plugins: [pinia, i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  });
  await flushPromises();
  return wrapper;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('DashboardView blob store compaction', () => {
  beforeEach(() => {
    list.mockReset();
    statusCall.mockReset();
    compactCall.mockReset();
    list.mockResolvedValue([]);
    statusCall.mockResolvedValue(systemStatus(1200, 5000));
  });

  it('offers administrators a Compact button per blob store', async () => {
    const wrapper = await mountView(['system:admin']);
    const buttons = wrapper.findAll('[data-testid^="compact-"]');
    expect(buttons.map((b) => b.attributes('data-testid'))).toEqual(['compact-default', 'compact-archive']);
    expect(buttons[0]!.text()).toBe('Compact');
    expect(buttons[0]!.attributes('aria-label')).toBe('Compact blob store default');
    expect(wrapper.text()).toContain('older than the grace period');
  });

  it('compacts the store, shows the report and refreshes the statistics', async () => {
    compactCall.mockResolvedValue(report());
    const wrapper = await mountView(['settings:manage']);
    expect(statusCall).toHaveBeenCalledTimes(1);
    statusCall.mockResolvedValue(systemStatus(1193, 2952));

    await wrapper.find('[data-testid="compact-default"]').trigger('click');
    await flushPromises();

    expect(compactCall).toHaveBeenCalledExactlyOnceWith('default');
    const shown = wrapper.find('[data-testid="compact-report-default"]');
    expect(shown.exists()).toBe(true);
    expect(shown.text()).toBe('7 of 1,200 blobs deleted, 2 kB reclaimed in 1.5 sec.');
    expect(wrapper.find('[data-testid="compact-report-archive"]').exists()).toBe(false);
    expect(statusCall).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-blob-store="default"]').text()).toContain('1,193 blobs');
    expect(useToastStore().toasts.map((t) => [t.kind, t.message])).toEqual([['success', 'Blob store default compacted.']]);
  });

  it('shows progress and blocks a second run while one is going', async () => {
    const pending = deferred<CompactionReport>();
    compactCall.mockReturnValue(pending.promise);
    const wrapper = await mountView(['system:admin']);

    await wrapper.find('[data-testid="compact-archive"]').trigger('click');
    await flushPromises();
    const archive = wrapper.find('[data-testid="compact-archive"]');
    expect(archive.text()).toBe('Compacting…');
    expect(archive.attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-testid="compact-default"]').attributes('disabled')).toBeDefined();
    await wrapper.find('[data-testid="compact-default"]').trigger('click');
    expect(compactCall).toHaveBeenCalledTimes(1);

    pending.resolve(report({ blobStore: 'archive', blobsScanned: 3, blobsDeleted: 0, bytesReclaimed: 0, durationMillis: 12 }));
    await flushPromises();
    expect(wrapper.find('[data-testid="compact-archive"]').text()).toBe('Compact');
    expect(wrapper.find('[data-testid="compact-archive"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="compact-report-archive"]').text()).toBe('0 of 3 blobs deleted, 0 byte reclaimed in 12 ms.');
  });

  it('reports a refused run with the translated server error', async () => {
    compactCall.mockRejectedValue(
      new ApiError({ code: 'blobStore.compaction.running', message: 'busy', details: {}, status: 409 }),
    );
    const wrapper = await mountView(['system:admin']);

    await wrapper.find('[data-testid="compact-default"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="compact-report-default"]').exists()).toBe(false);
    expect(useToastStore().toasts.map((t) => [t.kind, t.message])).toEqual([
      ['error', 'This blob store is already being compacted. Try again when the current run has finished.'],
    ]);
    expect(wrapper.find('[data-testid="compact-default"]').attributes('disabled')).toBeUndefined();
  });

  it('shows the statistics but no Compact button to repository managers', async () => {
    const wrapper = await mountView(['repositories:manage']);
    expect(wrapper.find('[data-blob-store="default"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid^="compact-"]')).toHaveLength(0);
    expect(wrapper.text()).not.toContain('older than the grace period');
  });

  it('reads right to left in Persian, with Persian digits in the report', async () => {
    compactCall.mockResolvedValue(report());
    const wrapper = await mountView(['system:admin']);
    const locale = useLocaleStore();
    locale.set('fa');
    try {
      await flushPromises();
      expect(document.documentElement.dir).toBe('rtl');
      const button = wrapper.find('[data-testid="compact-default"]');
      expect(button.text()).toBe('فشرده‌سازی');

      await button.trigger('click');
      await flushPromises();

      const text = wrapper.find('[data-testid="compact-report-default"]').text();
      expect(text).toContain('۷ از ۱٬۲۰۰ بلاب حذف شد');
      expect(text).not.toMatch(/[0-9]/);
    } finally {
      locale.set('en');
    }
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('speaks the language of the UI', async () => {
    i18n.global.locale.value = 'de';
    const wrapper = await mountView(['system:admin']);
    expect(wrapper.find('[data-testid="compact-default"]').text()).toBe('Bereinigen');
    expect(wrapper.find('[data-testid="compact-default"]').attributes('aria-label')).toBe('Blob-Speicher default bereinigen');
  });
});
