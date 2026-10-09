import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { nextTick, reactive } from 'vue';
import RepositoriesView from '@/views/RepositoriesView.vue';
import type { FormatView, RepositoryView, StandardRepositoriesResult } from '@/api/types';
import { useAuthStore } from '@/stores/auth';
import { mountWithPlugins } from '../helpers';

const list = vi.fn<() => Promise<RepositoryView[]>>();
const formatsCall = vi.fn<() => Promise<FormatView[]>>();
const createStandard = vi.fn<() => Promise<StandardRepositoriesResult>>();
const routeQuery = reactive<Record<string, string>>({});
const replace = vi.fn<(to: { query: Record<string, string | undefined> }) => Promise<void>>();

vi.mock('@/api', () => ({
  api: {
    formats: () => formatsCall(),
    repositories: {
      list: () => list(),
      createStandard: () => createStandard(),
    },
  },
}));

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: routeQuery, params: {}, path: '/repositories' }),
  useRouter: () => ({ replace }),
}));

function repo(name: string, format: string, type: RepositoryView['type'] = 'HOSTED', description: string | null = null): RepositoryView {
  return {
    id: name,
    name,
    format,
    type,
    online: true,
    blobStore: 'default',
    description,
    allowRedeploy: false,
    versionPolicy: 'MIXED',
    remoteUrl: type === 'PROXY' ? 'https://upstream.example/' : null,
    hasRemotePassword: false,
    hasRemoteToken: false,
    offlineMode: false,
    members: [],
    formatSettings: {},
    url: `http://localhost/repository/${name}/`,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    statistics: { assets: 0, components: 0, bytes: 0 },
    upstream: type === 'PROXY' ? { reachable: true, consecutiveFailures: 0 } : null,
  };
}

function format(id: string, displayName: string): FormatView {
  return { id, displayName, types: ['HOSTED', 'PROXY', 'GROUP'], settings: [], templates: [] };
}

const formats = [format('maven2', 'Maven'), format('npm', 'npm'), format('pypi', 'PyPI')];
const repositories = [
  repo('npm-proxy', 'npm', 'PROXY', 'Proxy of the public npm registry'),
  repo('maven-central', 'maven2', 'PROXY', 'Proxy of Maven Central'),
  repo('maven-releases', 'maven2'),
  repo('pypi-hosted', 'pypi'),
];

function headings(wrapper: ReturnType<typeof mountWithPlugins>): string[] {
  return wrapper.findAll('[data-testid="format-heading"]').map((h) => `${h.find('span > span').text()} ${h.find('.badge').text()}`);
}

function rows(wrapper: ReturnType<typeof mountWithPlugins>): string[] {
  return wrapper.findAll('tr[data-repository]').map((r) => r.attributes('data-repository')!);
}

async function mountView(privileges: string[] = ['system:admin']) {
  const wrapper = mountWithPlugins(RepositoriesView);
  const auth = useAuthStore();
  auth.privileges = privileges;
  await flushPromises();
  return wrapper;
}

describe('RepositoriesView', () => {
  beforeEach(() => {
    list.mockReset();
    formatsCall.mockReset();
    createStandard.mockReset();
    replace.mockReset();
    // the real router updates the current route; the mock does the same so the view sees its own change
    replace.mockImplementation(async (to) => {
      for (const key of Object.keys(routeQuery)) {
        delete routeQuery[key];
      }
      for (const [key, value] of Object.entries(to.query)) {
        if (value !== undefined) {
          routeQuery[key] = value;
        }
      }
    });
    for (const key of Object.keys(routeQuery)) {
      delete routeQuery[key];
    }
    list.mockResolvedValue(repositories);
    formatsCall.mockResolvedValue(formats);
  });

  it('groups the rows by format with one heading per group, display names from the formats list', async () => {
    const wrapper = await mountView();
    expect(headings(wrapper)).toEqual(['Maven 2', 'npm 1', 'PyPI 1']);
    expect(rows(wrapper)).toEqual(['maven-central', 'maven-releases', 'npm-proxy', 'pypi-hosted']);
    expect(wrapper.find('tr[data-repository="npm-proxy"]').text()).toContain('npm');
    expect(wrapper.find('[data-testid="filter-count"]').text()).toBe('4 of 4 repositories');
    expect(wrapper.find('[data-testid="filter-clear"]').exists()).toBe(false);
    const options = wrapper.find('[data-testid="filter-format"]').findAll('option').map((o) => o.text());
    expect(options).toEqual(['All formats', 'Maven', 'npm', 'PyPI']);
  });

  it('filters by name or description as you type', async () => {
    const wrapper = await mountView();
    await wrapper.find('[data-testid="filter-query"]').setValue('proxy of');
    expect(rows(wrapper)).toEqual(['maven-central', 'npm-proxy']);
    expect(headings(wrapper)).toEqual(['Maven 1', 'npm 1']);
    expect(wrapper.find('[data-testid="filter-count"]').text()).toBe('2 of 4 repositories');
    expect(wrapper.find('[data-testid="filter-clear"]').exists()).toBe(true);

    await wrapper.find('[data-testid="filter-query"]').setValue('nothing-like-this');
    expect(rows(wrapper)).toEqual([]);
    expect(wrapper.find('[data-testid="filter-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="filter-empty"]').text()).toContain('No repositories match the filter.');

    await wrapper.find('[data-testid="filter-clear"]').trigger('click');
    expect(rows(wrapper)).toHaveLength(4);
    expect((wrapper.find('[data-testid="filter-query"]').element as HTMLInputElement).value).toBe('');
  });

  it('a format filter hides the group headings, shows only that format and lands in the URL', async () => {
    const wrapper = await mountView();
    await wrapper.find('[data-testid="filter-format"]').setValue('maven2');
    expect(rows(wrapper)).toEqual(['maven-central', 'maven-releases']);
    expect(headings(wrapper)).toEqual([]);
    expect(wrapper.find('[data-testid="filter-count"]').text()).toBe('2 of 4 repositories');
    expect(replace).toHaveBeenLastCalledWith({ query: { format: 'maven2' } });

    await wrapper.find('[data-testid="filter-query"]').setValue('release');
    expect(rows(wrapper)).toEqual(['maven-releases']);
    // the text search stays out of the URL
    expect(replace).toHaveBeenCalledTimes(1);
    await wrapper.find('[data-testid="filter-format"]').setValue('');
    expect(headings(wrapper)).toEqual(['Maven 1']);
    expect(replace).toHaveBeenLastCalledWith({ query: { format: undefined } });
  });

  it('starts with the format from the route query and follows it when the route changes', async () => {
    routeQuery.format = 'npm';
    const wrapper = await mountView();
    expect((wrapper.find('[data-testid="filter-format"]').element as HTMLSelectElement).value).toBe('npm');
    expect(rows(wrapper)).toEqual(['npm-proxy']);
    expect(headings(wrapper)).toEqual([]);
    expect(replace).not.toHaveBeenCalled();

    // the navigation bar links to the unfiltered list: the view is reused, the filter must drop
    delete routeQuery.format;
    await nextTick();
    expect((wrapper.find('[data-testid="filter-format"]').element as HTMLSelectElement).value).toBe('');
    expect(rows(wrapper)).toHaveLength(4);
    expect(headings(wrapper)).toEqual(['Maven 2', 'npm 1', 'PyPI 1']);
    expect(replace).not.toHaveBeenCalled();

    routeQuery.format = 'pypi';
    await nextTick();
    expect(rows(wrapper)).toEqual(['pypi-hosted']);
    expect(replace).not.toHaveBeenCalled();
  });

  it('labels each format group as a row group heading', async () => {
    const wrapper = await mountView();
    const heading = wrapper.find('[data-testid="format-heading"]');
    expect(heading.attributes('scope')).toBe('rowgroup');
    expect(heading.attributes('colspan')).toBe('8');
    expect(wrapper.findAll('tbody')).toHaveLength(3);
  });

  it('falls back to the format id when the server does not list the format', async () => {
    list.mockResolvedValue([repo('hex-hosted', 'hex')]);
    const wrapper = await mountView();
    expect(headings(wrapper)).toEqual(['hex 1']);
  });

  it('reports how many standard repositories were created and reloads the list', async () => {
    createStandard.mockResolvedValue({ created: ['pypi-proxy', 'pypi-group'], templates: ['pypi-proxy', 'pypi-hosted', 'pypi-group'] });
    const wrapper = await mountView();
    expect(list).toHaveBeenCalledTimes(1);
    await wrapper.find('[data-testid="create-standard"]').trigger('click');
    await flushPromises();
    expect(createStandard).toHaveBeenCalledTimes(1);
    expect(list).toHaveBeenCalledTimes(2);
    const notice = wrapper.find('[data-testid="standard-result"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('2 standard repositories created: pypi-proxy, pypi-group');

    createStandard.mockResolvedValue({ created: [], templates: ['pypi-proxy'] });
    await wrapper.find('[data-testid="create-standard"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="standard-result"]').text()).toContain('All standard repositories already exist.');
  });

  it('hides the management buttons from users who cannot manage repositories', async () => {
    const wrapper = await mountView(['repository:read:*']);
    expect(wrapper.find('[data-testid="create-standard"]').exists()).toBe(false);
    expect(rows(wrapper)).toHaveLength(4);
  });
});
