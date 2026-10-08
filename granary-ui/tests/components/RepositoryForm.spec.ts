import { describe, expect, it } from 'vitest';
import { nextTick } from 'vue';
import RepositoryForm from '@/components/RepositoryForm.vue';
import { ApiError } from '@/api/client';
import type { RepositoryRequest, RepositoryView } from '@/api/types';
import { mountWithPlugins } from '../helpers';

const formats = [
  { id: 'maven2', displayName: 'Maven' },
  { id: 'npm', displayName: 'npm' },
  { id: 'nuget', displayName: 'NuGet' },
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
  it('shows the version policy for a hosted Maven repository but not for npm', async () => {
    const wrapper = mountForm();
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
