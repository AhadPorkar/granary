import { describe, expect, it } from 'vitest';
import PrivilegePicker from '@/components/PrivilegePicker.vue';
import { mountWithPlugins } from '../helpers';

const wellKnown = ['system:admin', 'repositories:manage', 'users:manage', 'settings:manage', 'repository:read:*', 'repository:write:*'];

describe('PrivilegePicker', () => {
  it('renders one labelled checkbox per well-known privilege', () => {
    const wrapper = mountWithPlugins(PrivilegePicker, { props: { modelValue: [], wellKnown, repositories: [] } });
    for (const privilege of wellKnown) {
      expect(wrapper.find(`input[data-privilege="${privilege}"]`).exists(), privilege).toBe(true);
    }
    expect(wrapper.text()).toContain('System administrator');
    expect(wrapper.text()).toContain('Write to all repositories');
    expect(wrapper.text()).toContain('No repositories exist yet.');
  });

  it('renders a read/write row per repository and reflects the selection', () => {
    const wrapper = mountWithPlugins(PrivilegePicker, {
      props: { modelValue: ['repository:read:npm-proxy', 'users:manage'], wellKnown, repositories: ['maven-releases', 'npm-proxy'] },
    });
    expect(wrapper.findAll('[data-repository-row]')).toHaveLength(2);
    expect((wrapper.find('input[data-privilege="repository:read:npm-proxy"]').element as HTMLInputElement).checked).toBe(true);
    expect((wrapper.find('input[data-privilege="repository:write:npm-proxy"]').element as HTMLInputElement).checked).toBe(false);
    expect((wrapper.find('input[data-privilege="users:manage"]').element as HTMLInputElement).checked).toBe(true);
    expect((wrapper.find('input[data-privilege="system:admin"]').element as HTMLInputElement).checked).toBe(false);
  });

  it('emits the updated privilege list when boxes are toggled', async () => {
    const wrapper = mountWithPlugins(PrivilegePicker, {
      props: { modelValue: ['repository:read:npm-proxy'], wellKnown, repositories: ['npm-proxy'] },
    });
    await wrapper.find('input[data-privilege="repository:write:npm-proxy"]').setValue(true);
    expect(wrapper.emitted('update:modelValue')![0][0]).toEqual(['repository:read:npm-proxy', 'repository:write:npm-proxy']);

    await wrapper.find('input[data-privilege="repository:read:npm-proxy"]').setValue(false);
    expect(wrapper.emitted('update:modelValue')![1][0]).toEqual([]);

    await wrapper.find('input[data-privilege="system:admin"]').setValue(true);
    expect(wrapper.emitted('update:modelValue')![2][0]).toEqual(['repository:read:npm-proxy', 'system:admin']);
  });

  it('keeps privileges that match no known repository and lists them separately', () => {
    const wrapper = mountWithPlugins(PrivilegePicker, {
      props: { modelValue: ['repository:write:deleted-repo'], wellKnown, repositories: ['npm-proxy'] },
    });
    expect(wrapper.text()).toContain('Other privileges');
    const other = wrapper.find('input[data-privilege="repository:write:deleted-repo"]');
    expect(other.exists()).toBe(true);
    expect((other.element as HTMLInputElement).checked).toBe(true);
  });
});
