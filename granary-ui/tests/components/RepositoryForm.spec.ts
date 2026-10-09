import { describe, expect, it } from 'vitest';
import { nextTick } from 'vue';
import RepositoryForm from '@/components/RepositoryForm.vue';
import { ApiError } from '@/api/client';
import type { FormatView, RepositoryRequest, RepositoryView, SettingDescriptor } from '@/api/types';
import { mountWithPlugins } from '../helpers';

const ALL: FormatView['types'] = ['HOSTED', 'PROXY', 'GROUP'];

const dockerSettings: SettingDescriptor[] = [
  { key: 'docker.v1Enabled', label: 'Enable V1 API', type: 'BOOLEAN', defaultValue: 'false', description: 'Serve the legacy V1 endpoints as well.', appliesTo: ALL },
  { key: 'docker.httpPort', label: 'HTTP port', type: 'INTEGER', defaultValue: null, description: 'Extra connector port for this registry.', appliesTo: ['HOSTED', 'GROUP'] },
  { key: 'docker.indexUrl', label: 'Index URL', type: 'URL', defaultValue: 'https://index.docker.io/', description: 'Where docker search looks things up.', appliesTo: ['PROXY'] },
  { key: 'docker.defaultRepository', label: 'Default repository', type: 'STRING', defaultValue: 'library', description: 'Namespace for images without one.', appliesTo: ALL },
];

const formats: FormatView[] = [
  { id: 'nuget', displayName: 'NuGet', types: ALL, settings: [], templates: ['nuget.org', 'nuget-hosted', 'nuget-group'] },
  { id: 'maven2', displayName: 'Maven', types: ALL, settings: [], templates: ['maven-central', 'maven-releases', 'maven-public'] },
  { id: 'npm', displayName: 'npm', types: ALL, settings: [], templates: ['npm-proxy', 'npm-hosted', 'npm-group'] },
  { id: 'docker', displayName: 'Docker', types: ALL, settings: dockerSettings, templates: ['docker-hub', 'docker-hosted'] },
  { id: 'go', displayName: 'Go', types: ['PROXY', 'GROUP'], settings: [], templates: [] },
  { id: 'raw', displayName: 'Raw', types: ['HOSTED'], settings: [{ key: 'raw.listing', label: 'Directory listing', type: 'BOOLEAN', defaultValue: 'true', description: 'Render an index for directories.', appliesTo: ['HOSTED'] }], templates: [] },
];

function repo(name: string, format: string, type: RepositoryView['type'] = 'HOSTED'): RepositoryView {
  return {
    id: name,
    name,
    format,
    type,
    online: true,
    blobStore: 'default',
    description: null,
    allowRedeploy: false,
    versionPolicy: 'MIXED',
    remoteUrl: type === 'PROXY' ? 'https://repo1.maven.org/maven2/' : null,
    remoteUsername: null,
    hasRemotePassword: false,
    hasRemoteToken: false,
    connectTimeoutSeconds: null,
    readTimeoutSeconds: null,
    metadataMaxAgeMinutes: null,
    negativeCacheMinutes: null,
    offlineMode: false,
    members: [],
    formatSettings: {},
    url: `http://localhost/repository/${name}/`,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    statistics: { assets: 0, components: 0, bytes: 0 },
    upstream: null,
  };
}

const repositories = [repo('maven-releases', 'maven2'), repo('maven-central', 'maven2', 'PROXY'), repo('npm-hosted', 'npm')];

function mountForm(props: Partial<InstanceType<typeof RepositoryForm>['$props']> = {}) {
  return mountWithPlugins(RepositoryForm, {
    props: { mode: 'create', formats, repositories, ...props },
  });
}

describe('RepositoryForm', () => {
  it('lists the formats by display name and preselects the first one', () => {
    const wrapper = mountForm();
    const options = wrapper.find('[data-testid="format-select"]').findAll('option').map((o) => o.text());
    expect(options).toEqual(['Docker', 'Go', 'Maven', 'npm', 'NuGet', 'Raw']);
    expect((wrapper.find('[data-testid="format-select"]').element as HTMLSelectElement).value).toBe('docker');
  });

  it('shows the version policy for a hosted Maven repository but not for npm', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('maven2');
    expect(wrapper.find('[data-testid="hosted-section"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="version-policy"]').exists()).toBe(true);
    expect(wrapper.find('#repo-allow-redeploy').exists()).toBe(true);

    await wrapper.find('[data-testid="format-select"]').setValue('npm');
    expect(wrapper.find('[data-testid="version-policy"]').exists()).toBe(false);
    expect(wrapper.find('#repo-allow-redeploy').exists()).toBe(true);
    expect(wrapper.find('[data-testid="proxy-section"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="group-section"]').exists()).toBe(false);
  });

  it('shows remote URL, timeouts, cache and credential fields for a proxy', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="type-PROXY"]').setValue();
    expect(wrapper.find('[data-testid="proxy-section"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="hosted-section"]').exists()).toBe(false);
    for (const id of ['#repo-remote-url', '#repo-connect-timeout', '#repo-read-timeout', '#repo-metadata-max-age', '#repo-negative-cache', '#repo-offline-mode', '#repo-remote-username', '#repo-remote-password', '#repo-remote-token']) {
      expect(wrapper.find(id).exists(), id).toBe(true);
    }
  });

  it('offers only repositories of the same format as group members, never itself', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('maven2');
    await wrapper.find('#repo-name').setValue('maven-releases');
    await wrapper.find('[data-testid="type-GROUP"]').setValue();
    const picker = wrapper.find('[data-testid="member-picker"]');
    expect(picker.exists()).toBe(true);
    const options = picker.findAll('option').map((o) => o.text());
    expect(options).toContain('maven-central');
    expect(options).not.toContain('maven-releases');
    expect(options).not.toContain('npm-hosted');

    await wrapper.find('[data-testid="format-select"]').setValue('npm');
    const npmOptions = wrapper.find('[data-testid="member-picker"]').findAll('option').map((o) => o.text());
    expect(npmOptions).toContain('npm-hosted');
    expect(npmOptions).not.toContain('maven-central');
  });

  it('refuses to submit a proxy without a valid remote URL', async () => {
    const wrapper = mountForm();
    await wrapper.find('#repo-name').setValue('central');
    await wrapper.find('[data-testid="type-PROXY"]').setValue();
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted('submit')).toBeUndefined();
    expect(wrapper.find('[data-field="repo-remote-url"] .field__error').exists()).toBe(true);

    await wrapper.find('#repo-remote-url').setValue('not a url');
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted('submit')).toBeUndefined();

    await wrapper.find('#repo-remote-url').setValue('https://registry.npmjs.org/');
    await wrapper.find('#repo-read-timeout').setValue('120');
    await wrapper.find('form').trigger('submit');
    const [request] = wrapper.emitted<[RepositoryRequest]>('submit')![0];
    expect(request).toMatchObject({ name: 'central', type: 'PROXY', remoteUrl: 'https://registry.npmjs.org/', readTimeoutSeconds: 120, connectTimeoutSeconds: null, members: [] });
  });

  it('emits a group request with the ordered members and requires at least one', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('maven2');
    await wrapper.find('#repo-name').setValue('maven-all');
    await wrapper.find('[data-testid="type-GROUP"]').setValue();
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted('submit')).toBeUndefined();
    expect(wrapper.find('[data-field="repo-members"] .field__error').exists()).toBe(true);

    const picker = wrapper.find('[data-testid="member-picker"]');
    await picker.find('select').setValue('maven-central');
    await picker.find('button.btn:not(.btn--icon)').trigger('click');
    await wrapper.find('[data-testid="member-picker"] select').setValue('maven-releases');
    await wrapper.find('[data-testid="member-picker"] button.btn:not(.btn--icon)').trigger('click');
    await wrapper.find('form').trigger('submit');
    const [request] = wrapper.emitted<[RepositoryRequest]>('submit')![0];
    expect(request.type).toBe('GROUP');
    expect(request.members).toEqual(['maven-central', 'maven-releases']);
    expect(request.remoteUrl).toBeUndefined();
    expect(request.formatSettings).toEqual({});
  });

  it('offers only the repository types the selected format supports and switches away from a forbidden one', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('maven2');
    expect(wrapper.findAll('input[name="repo-type"]')).toHaveLength(3);
    await wrapper.find('[data-testid="type-PROXY"]').setValue();
    expect(wrapper.find('[data-testid="proxy-section"]').exists()).toBe(true);

    // Raw is hosted-only: the proxy choice disappears and the type falls back to hosted
    await wrapper.find('[data-testid="format-select"]').setValue('raw');
    expect(wrapper.findAll('input[name="repo-type"]').map((i) => i.attributes('value'))).toEqual(['HOSTED']);
    expect((wrapper.find('[data-testid="type-HOSTED"]').element as HTMLInputElement).checked).toBe(true);
    expect(wrapper.find('[data-testid="hosted-section"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="proxy-section"]').exists()).toBe(false);

    // Go has no hosted repositories: the first allowed type (proxy) is selected
    await wrapper.find('[data-testid="format-select"]').setValue('go');
    expect(wrapper.findAll('input[name="repo-type"]').map((i) => i.attributes('value'))).toEqual(['PROXY', 'GROUP']);
    expect((wrapper.find('[data-testid="type-PROXY"]').element as HTMLInputElement).checked).toBe(true);

    // an allowed type is kept when switching to a format that supports it too
    await wrapper.find('[data-testid="type-GROUP"]').setValue();
    await wrapper.find('[data-testid="format-select"]').setValue('npm');
    expect((wrapper.find('[data-testid="type-GROUP"]').element as HTMLInputElement).checked).toBe(true);
  });

  it('names the standard repositories of the selected format in create mode only', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('maven2');
    expect(wrapper.find('[data-field="repo-format"] .field__hint').text()).toBe('Standard repositories of this format: maven-central, maven-releases, maven-public');
    await wrapper.find('[data-testid="format-select"]').setValue('go');
    expect(wrapper.find('[data-field="repo-format"] .field__hint').exists()).toBe(false);

    const edit = mountForm({ mode: 'edit', initial: repo('maven-central', 'maven2', 'PROXY') });
    await nextTick();
    expect(edit.find('[data-field="repo-format"] .field__hint').exists()).toBe(false);
  });

  it('renders one field per format setting that applies to the selected type', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('maven2');
    expect(wrapper.find('[data-testid="format-settings-section"]').exists()).toBe(false);

    await wrapper.find('[data-testid="format-select"]').setValue('docker');
    const section = wrapper.find('[data-testid="format-settings-section"]');
    expect(section.exists()).toBe(true);
    expect(section.text()).toContain('Format settings');
    expect(section.text()).toContain('Settings specific to Docker.');
    // hosted: the boolean, the integer and the string, not the proxy-only URL
    expect(section.findAll('[data-setting]').map((el) => el.attributes('data-setting'))).toEqual(['docker.v1Enabled', 'docker.httpPort', 'docker.defaultRepository']);
    const toggle = section.find('#repo-setting-docker-v1Enabled');
    expect(toggle.attributes('type')).toBe('checkbox');
    expect(toggle.attributes('role')).toBe('switch');
    expect((toggle.element as HTMLInputElement).checked).toBe(false);
    const port = section.find('[data-setting="docker.httpPort"]');
    expect(port.attributes('type')).toBe('number');
    expect(port.attributes('placeholder')).toBeUndefined();
    const name = section.find('[data-setting="docker.defaultRepository"]');
    expect(name.attributes('type')).toBe('text');
    expect(name.attributes('placeholder')).toBe('library');
    expect(section.text()).toContain('Enable V1 API');
    expect(section.text()).toContain('Serve the legacy V1 endpoints as well.');
    expect(section.text()).toContain('Namespace for images without one.');

    await wrapper.find('[data-testid="type-PROXY"]').setValue();
    const proxySection = wrapper.find('[data-testid="format-settings-section"]');
    expect(proxySection.findAll('[data-setting]').map((el) => el.attributes('data-setting'))).toEqual(['docker.v1Enabled', 'docker.indexUrl', 'docker.defaultRepository']);
    const url = proxySection.find('[data-setting="docker.indexUrl"]');
    expect(url.attributes('type')).toBe('url');
    expect(url.attributes('placeholder')).toBe('https://index.docker.io/');
  });

  it('maps settings into formatSettings as strings, booleans as "true"/"false", and drops blanks', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('docker');
    await wrapper.find('#repo-name').setValue('docker-hosted');
    await wrapper.find('#repo-setting-docker-v1Enabled').setValue(true);
    await wrapper.find('[data-setting="docker.httpPort"]').setValue('5000');
    await wrapper.find('[data-setting="docker.defaultRepository"]').setValue('   ');
    await wrapper.find('form').trigger('submit');
    let [request] = wrapper.emitted<[RepositoryRequest]>('submit')!.at(-1)!;
    expect(request.formatSettings).toEqual({ 'docker.v1Enabled': 'true', 'docker.httpPort': '5000' });

    await wrapper.find('#repo-setting-docker-v1Enabled').setValue(false);
    await wrapper.find('[data-setting="docker.httpPort"]').setValue('');
    await wrapper.find('[data-setting="docker.defaultRepository"]').setValue(' acme ');
    await wrapper.find('form').trigger('submit');
    [request] = wrapper.emitted<[RepositoryRequest]>('submit')!.at(-1)!;
    expect(request.formatSettings).toEqual({ 'docker.v1Enabled': 'false', 'docker.defaultRepository': 'acme' });
  });

  it('does not send a setting that no longer applies after the type changed', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('docker');
    await wrapper.find('#repo-name').setValue('docker-x');
    await wrapper.find('[data-setting="docker.httpPort"]').setValue('5000');
    await wrapper.find('[data-testid="type-PROXY"]').setValue();
    await wrapper.find('#repo-remote-url').setValue('https://registry-1.docker.io/');
    await wrapper.find('[data-setting="docker.indexUrl"]').setValue('https://index.docker.io/');
    await wrapper.find('form').trigger('submit');
    const [request] = wrapper.emitted<[RepositoryRequest]>('submit')![0];
    expect(request.formatSettings).toEqual({ 'docker.indexUrl': 'https://index.docker.io/' });
  });

  it('forgets the settings of the previous format when a new repository switches format', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('docker');
    await wrapper.find('#repo-name').setValue('files');
    await wrapper.find('[data-setting="docker.httpPort"]').setValue('5000');
    await wrapper.find('#repo-setting-docker-v1Enabled').setValue(true);
    await wrapper.find('[data-testid="format-select"]').setValue('raw');
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted<[RepositoryRequest]>('submit')![0][0]).toMatchObject({ format: 'raw', type: 'HOSTED', formatSettings: {} });

    // and back again: the fields start blank, the toggle at its default
    await wrapper.find('[data-testid="format-select"]').setValue('docker');
    expect((wrapper.find('[data-setting="docker.httpPort"]').element as HTMLInputElement).value).toBe('');
    expect((wrapper.find('#repo-setting-docker-v1Enabled').element as HTMLInputElement).checked).toBe(false);
  });

  it('refuses a non-integer or a malformed URL in a format setting', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('docker');
    await wrapper.find('#repo-name').setValue('docker-x');
    // a number input sanitises letters away, but a fraction gets through and is not a whole number
    await wrapper.find('[data-setting="docker.httpPort"]').setValue('5.5');
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted('submit')).toBeUndefined();
    expect(wrapper.find('[data-field="repo-setting-docker-httpPort"] .field__error').text()).toBe('Enter a whole number.');

    await wrapper.find('[data-setting="docker.httpPort"]').setValue('5000');
    await wrapper.find('[data-testid="type-PROXY"]').setValue();
    await wrapper.find('#repo-remote-url').setValue('https://registry-1.docker.io/');
    await wrapper.find('[data-setting="docker.indexUrl"]').setValue('not a url');
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted('submit')).toBeUndefined();
    expect(wrapper.find('[data-field="repo-setting-docker-indexUrl"] .field__error').text()).toBe('Enter an absolute http(s) URL.');
  });

  it('a boolean setting shows the server default until it is toggled', async () => {
    const wrapper = mountForm();
    await wrapper.find('[data-testid="format-select"]').setValue('raw');
    await wrapper.find('#repo-name').setValue('files');
    const toggle = wrapper.find('#repo-setting-raw-listing');
    expect((toggle.element as HTMLInputElement).checked).toBe(true);
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted<[RepositoryRequest]>('submit')![0][0].formatSettings).toEqual({});

    await toggle.setValue(false);
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted<[RepositoryRequest]>('submit')![1][0].formatSettings).toEqual({ 'raw.listing': 'false' });
  });

  it('in edit mode loads the stored format settings and passes unknown keys through', async () => {
    const initial = { ...repo('docker-hosted', 'docker'), formatSettings: { 'docker.v1Enabled': 'true', 'docker.httpPort': '5000', 'docker.legacy': 'keep' } };
    const wrapper = mountForm({ mode: 'edit', initial });
    await nextTick();
    expect((wrapper.find('#repo-setting-docker-v1Enabled').element as HTMLInputElement).checked).toBe(true);
    expect((wrapper.find('[data-setting="docker.httpPort"]').element as HTMLInputElement).value).toBe('5000');
    expect((wrapper.find('[data-setting="docker.defaultRepository"]').element as HTMLInputElement).value).toBe('');
    // the type cannot change in edit mode, so a hosted-only format keeps its single type too
    expect((wrapper.find('[data-testid="type-HOSTED"]').element as HTMLInputElement).disabled).toBe(true);

    await wrapper.find('[data-setting="docker.httpPort"]').setValue('5001');
    await wrapper.find('form').trigger('submit');
    const [request] = wrapper.emitted<[RepositoryRequest]>('submit')![0];
    expect(request.formatSettings).toEqual({ 'docker.v1Enabled': 'true', 'docker.httpPort': '5001', 'docker.legacy': 'keep' });
  });

  it('in edit mode keeps a type the format no longer lists instead of switching it', async () => {
    const initial = repo('go-hosted', 'go');
    const wrapper = mountForm({ mode: 'edit', initial });
    await nextTick();
    expect(wrapper.find('[data-testid="hosted-section"]').exists()).toBe(true);
    await wrapper.find('form').trigger('submit');
    expect(wrapper.emitted<[RepositoryRequest]>('submit')![0][0].type).toBe('HOSTED');
  });

  it('in edit mode locks name, format and type and pre-fills the stored values', async () => {
    const initial = { ...repo('maven-central', 'maven2', 'PROXY'), description: 'Central mirror', readTimeoutSeconds: 90, hasRemotePassword: true, remoteUsername: 'svc' };
    const wrapper = mountForm({ mode: 'edit', initial });
    await nextTick();
    expect((wrapper.find('#repo-name').element as HTMLInputElement).readOnly).toBe(true);
    expect((wrapper.find('[data-testid="format-select"]').element as HTMLSelectElement).disabled).toBe(true);
    expect((wrapper.find('[data-testid="type-PROXY"]').element as HTMLInputElement).disabled).toBe(true);
    expect((wrapper.find('#repo-remote-url').element as HTMLInputElement).value).toBe('https://repo1.maven.org/maven2/');
    expect((wrapper.find('#repo-read-timeout').element as HTMLInputElement).value).toBe('90');
    expect((wrapper.find('#repo-remote-password').element as HTMLInputElement).placeholder).toContain('unchanged');
    expect(wrapper.find('[data-testid="submit"]').text()).toBe('Save');
  });

  it('shows an API error next to the field it belongs to', async () => {
    const wrapper = mountForm();
    await wrapper.setProps({ error: new ApiError({ code: 'repository.name.taken', message: 'taken', details: {}, status: 409 }) });
    const nameError = wrapper.find('[data-field="repo-name"] .field__error');
    expect(nameError.exists()).toBe(true);
    expect(nameError.text()).toBe('A repository with this name already exists.');
    expect(wrapper.find('.alert--danger').exists()).toBe(false);

    await wrapper.setProps({ error: new ApiError({ code: 'internal', message: 'boom', details: {}, status: 500 }) });
    expect(wrapper.find('.alert--danger').exists()).toBe(true);
  });
});
