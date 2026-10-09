import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import ConnectPanel from '@/components/ConnectPanel.vue';
import { ApiError } from '@/api/client';
import type { ConnectSnippet, RepositoryView } from '@/api/types';
import { mountWithPlugins } from '../helpers';

const connect = vi.fn<(name: string) => Promise<ConnectSnippet[]>>();

vi.mock('@/api', () => ({
  api: {
    repositories: {
      connect: (name: string) => connect(name),
    },
  },
}));

function repo(name: string, format: string): RepositoryView {
  return {
    id: name,
    name,
    format,
    type: 'HOSTED',
    online: true,
    blobStore: 'default',
    description: null,
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

const snippets: ConnectSnippet[] = [
  { id: 'maven-settings', title: 'Maven: settings', language: 'xml', filename: '~/.m2/settings.xml', code: '<settings>\n  <username>alice</username>\n  <password><api-token></password>\n</settings>' },
  { id: 'maven-deploy', title: 'Maven: deploy', language: 'shell', filename: null, code: 'mvn deploy' },
  { id: 'gradle', title: 'Gradle', language: 'text', code: 'maven { url = uri("http://localhost/repository/maven-releases") }' },
];

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('ConnectPanel', () => {
  beforeEach(() => {
    connect.mockReset();
  });

  it('shows a loading indicator until the snippets arrive, then one code block per snippet', async () => {
    const pending = deferred<ConnectSnippet[]>();
    connect.mockReturnValue(pending.promise);
    const wrapper = mountWithPlugins(ConnectPanel, { props: { repository: repo('maven-releases', 'maven2') } });
    expect(connect).toHaveBeenCalledWith('maven-releases');
    expect(wrapper.find('.loading').exists()).toBe(true);
    expect(wrapper.findAll('.code-block')).toHaveLength(0);
    // the URL field is there from the start
    expect((wrapper.find('input[readonly]').element as HTMLInputElement).value).toBe('http://localhost/repository/maven-releases/');

    pending.resolve(snippets);
    await flushPromises();
    expect(wrapper.find('.loading').exists()).toBe(false);
    const blocks = wrapper.findAll('.code-section');
    expect(blocks).toHaveLength(3);
    expect(blocks[0].find('h3').text()).toContain('Maven: settings');
    expect(blocks[0].find('[data-testid="code-filename"]').text()).toBe('~/.m2/settings.xml');
    expect(blocks[0].find('[data-testid="code-language"]').text()).toBe('xml');
    expect(blocks[0].find('pre code').text()).toContain('<password><api-token></password>');
    expect(blocks[0].find('pre code').text()).toContain('<username>alice</username>');
    // a command line has no filename badge
    expect(blocks[1].find('[data-testid="code-filename"]').exists()).toBe(false);
    expect(blocks[1].find('[data-testid="code-language"]').text()).toBe('shell');
    expect(blocks[1].find('pre code').text()).toBe('mvn deploy');
    expect(blocks[2].find('[data-testid="code-filename"]').exists()).toBe(false);
    expect(wrapper.find('[data-snippet="gradle"]').exists()).toBe(true);
    expect(wrapper.find('.empty').exists()).toBe(false);
    expect(wrapper.find('.alert--danger').exists()).toBe(false);
  });

  it('code blocks are always left-to-right', async () => {
    connect.mockResolvedValue(snippets);
    const wrapper = mountWithPlugins(ConnectPanel, { props: { repository: repo('maven-releases', 'maven2') } });
    await flushPromises();
    for (const pre of wrapper.findAll('pre.code-block')) {
      expect(pre.attributes('dir')).toBe('ltr');
    }
    expect(wrapper.find('pre.code-block').attributes('data-language')).toBe('xml');
  });

  it('shows an error alert when the request fails', async () => {
    connect.mockRejectedValue(new ApiError({ code: 'auth.forbidden', message: 'nope', details: {}, status: 403 }));
    const wrapper = mountWithPlugins(ConnectPanel, { props: { repository: repo('maven-releases', 'maven2') } });
    await flushPromises();
    expect(wrapper.find('.loading').exists()).toBe(false);
    const alert = wrapper.find('.alert--danger');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('You do not have permission to do this.');
    expect(wrapper.findAll('.code-section')).toHaveLength(0);
    expect(wrapper.find('.empty').exists()).toBe(false);
  });

  it('shows an empty state when the format has no snippets', async () => {
    connect.mockResolvedValue([]);
    const wrapper = mountWithPlugins(ConnectPanel, { props: { repository: repo('raw-files', 'raw') } });
    await flushPromises();
    const empty = wrapper.find('.empty');
    expect(empty.exists()).toBe(true);
    expect(empty.text()).toContain('This format has no ready-made snippets yet.');
    expect(wrapper.findAll('.code-section')).toHaveLength(0);
  });

  it('reloads when the repository changes', async () => {
    connect.mockResolvedValueOnce(snippets).mockResolvedValueOnce([snippets[1]]);
    const wrapper = mountWithPlugins(ConnectPanel, { props: { repository: repo('maven-releases', 'maven2') } });
    await flushPromises();
    expect(wrapper.findAll('.code-section')).toHaveLength(3);

    await wrapper.setProps({ repository: repo('maven-snapshots', 'maven2') });
    await flushPromises();
    expect(connect).toHaveBeenCalledTimes(2);
    expect(connect).toHaveBeenLastCalledWith('maven-snapshots');
    expect(wrapper.findAll('.code-section')).toHaveLength(1);
    expect((wrapper.find('input[readonly]').element as HTMLInputElement).value).toBe('http://localhost/repository/maven-snapshots/');
  });

  it('shows the anonymous hint only when anonymous reads are enabled', async () => {
    connect.mockResolvedValue([]);
    const withHint = mountWithPlugins(ConnectPanel, { props: { repository: repo('a', 'npm'), anonymousRead: true } });
    const without = mountWithPlugins(ConnectPanel, { props: { repository: repo('a', 'npm'), anonymousRead: false } });
    await flushPromises();
    expect(withHint.text()).toContain('Credentials are optional for downloads');
    expect(without.text()).not.toContain('Credentials are optional for downloads');
  });
});
