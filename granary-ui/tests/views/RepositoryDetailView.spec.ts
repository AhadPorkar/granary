import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import RepositoryDetailView from '@/views/RepositoryDetailView.vue';
import type { AssetView, ComponentView, FormatView, Page, RepositoryView } from '@/api/types';
import { i18n } from '@/i18n';
import { useAuthStore } from '@/stores/auth';
import { freshPinia } from '../helpers';

const getRepository = vi.fn<(name: string) => Promise<RepositoryView>>();
const listComponents = vi.fn<() => Promise<Page<ComponentView>>>();
const componentAssets = vi.fn<() => Promise<AssetView[]>>();

vi.mock('@/api', () => ({
  api: {
    formats: () =>
      Promise.resolve([
        { id: 'raw', displayName: 'Raw (generic files)', types: ['HOSTED'], settings: [], templates: [] },
        { id: 'maven2', displayName: 'Maven', types: ['HOSTED', 'PROXY', 'GROUP'], settings: [], templates: [] },
      ] as FormatView[]),
    system: { info: () => Promise.resolve(null) },
    repositories: {
      get: (name: string) => getRepository(name),
      components: () => listComponents(),
      componentAssets: () => componentAssets(),
    },
  },
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

function repository(name: string, format: string): RepositoryView {
  return {
    id: name,
    name,
    format,
    type: 'HOSTED',
    online: true,
    blobStore: 'default',
    description: 'Plain files',
    allowRedeploy: true,
    versionPolicy: 'MIXED',
    hasRemotePassword: false,
    hasRemoteToken: false,
    offlineMode: false,
    members: [],
    formatSettings: {},
    url: `http://localhost/repository/${name}/`,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    statistics: { assets: 1, components: 1, bytes: 2048 },
  } as RepositoryView;
}

function component(id: string, namespace: string, name: string, version: string): ComponentView {
  return { id, repositoryId: 'r', repository: 'raw-hosted', format: 'raw', namespace, name, version, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z' };
}

async function mountView(name: string) {
  const pinia = freshPinia();
  useAuthStore().privileges = ['repositories:manage'];
  const wrapper = mount(RepositoryDetailView, {
    props: { name },
    global: { plugins: [pinia, i18n], stubs: { RouterLink: { template: '<a><slot /></a>' }, ConnectPanel: true } },
  });
  await flushPromises();
  return wrapper;
}

describe('RepositoryDetailView components', () => {
  beforeEach(() => {
    getRepository.mockReset();
    listComponents.mockReset();
    componentAssets.mockReset();
  });

  it('leaves out the namespace column for a format without namespaces and breaks paths after slashes', async () => {
    getRepository.mockResolvedValue(repository('raw-hosted', 'raw'));
    listComponents.mockResolvedValue({ items: [component('c1', '', 'docs/handbook/2026', 'release-notes-0.2.0.pdf')], total: 1, page: 0, size: 25 });
    const wrapper = await mountView('raw-hosted');

    const headings = wrapper.findAll('thead th').map((th) => th.text());
    expect(headings).not.toContain('Namespace');
    expect(headings).toContain('Version');
    const name = wrapper.find('tbody td.component-cell strong');
    expect(name.text()).toBe('docs/handbook/2026');
    expect(name.findAll('wbr')).toHaveLength(2);
    // the version is repeated inside the name cell for phones, where the version column is hidden
    expect(wrapper.find('[data-testid="component-version-inline"]').text()).toBe('Version: release-notes-0.2.0.pdf');
    expect(wrapper.findAll('th.col-wide').map((th) => th.text())).toEqual(['Version', 'Updated']);
  });

  it('shows the namespace column when any component has one', async () => {
    getRepository.mockResolvedValue(repository('maven-releases', 'maven2'));
    listComponents.mockResolvedValue({
      items: [component('c1', 'com.example', 'widget', '1.0.0'), component('c2', '', 'plain', '2.0.0')],
      total: 2,
      page: 0,
      size: 25,
    });
    const wrapper = await mountView('maven-releases');
    expect(wrapper.findAll('thead th').map((th) => th.text())).toContain('Namespace');
    expect(wrapper.findAll('tbody > tr')[0]!.find('.narrow-only code').text()).toBe('com.example');
  });

  it('opens the assets of a component in a table that stacks on phones, with named copy buttons', async () => {
    getRepository.mockResolvedValue(repository('raw-hosted', 'raw'));
    listComponents.mockResolvedValue({ items: [component('c1', '', 'tools/cli', 'cli-linux-amd64.tar.gz')], total: 1, page: 0, size: 25 });
    componentAssets.mockResolvedValue([
      {
        id: 'a1',
        repositoryId: 'r',
        path: 'tools/cli/cli-linux-amd64.tar.gz',
        sha256: 'ab'.repeat(32),
        size: 4096,
        origin: 'UPLOADED',
        metadata: false,
        downloadCount: 3,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      } as AssetView,
    ]);
    const wrapper = await mountView('raw-hosted');
    await wrapper.find('.expand-toggle').trigger('click');
    await flushPromises();

    const nested = wrapper.find('table.table--nested');
    expect(nested.classes()).toContain('table--stack');
    expect(nested.findAll('td[data-label]').map((td) => td.attributes('data-label'))).toEqual(['Size', 'SHA-256', 'Downloads', 'Origin']);
    const copy = nested.find('.sha-cell button');
    expect(copy.attributes('aria-label')).toBe('Copy the SHA-256 of tools/cli/cli-linux-amd64.tar.gz');
    expect(copy.attributes('title')).toBe('ab'.repeat(32));
  });

  it('shows the translated format name in Persian', async () => {
    i18n.global.locale.value = 'fa';
    getRepository.mockResolvedValue(repository('raw-hosted', 'raw'));
    listComponents.mockResolvedValue({ items: [], total: 0, page: 0, size: 25 });
    const wrapper = await mountView('raw-hosted');
    expect(wrapper.text()).toContain(i18n.global.t('formatText.formats.raw', {}, { locale: 'fa' }));
    expect(wrapper.text()).not.toContain('Raw (generic files)');
  });
});
