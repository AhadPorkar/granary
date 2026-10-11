import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import ToggleSwitch from '@/components/ToggleSwitch.vue';

describe('ToggleSwitch', () => {
  it('is named by its label alone and described by its hint', () => {
    const wrapper = mount(ToggleSwitch, { props: { modelValue: true, label: 'Directory listing', hint: 'Render an index for directories.', id: 'raw-listing' } });
    const input = wrapper.find('input');
    expect(input.attributes('role')).toBe('switch');
    expect(input.attributes('id')).toBe('raw-listing');
    expect(input.attributes('aria-labelledby')).toBe('raw-listing-label');
    expect(input.attributes('aria-describedby')).toBe('raw-listing-hint');
    expect(wrapper.find('#raw-listing-label').text()).toBe('Directory listing');
    expect(wrapper.find('#raw-listing-hint').text()).toBe('Render an index for directories.');
    // the checked state comes from the native checkbox, not from a second ARIA attribute
    expect(input.attributes('aria-checked')).toBeUndefined();
    expect((input.element as HTMLInputElement).checked).toBe(true);
  });

  it('has no description without a hint', () => {
    const wrapper = mount(ToggleSwitch, { props: { modelValue: false, label: 'Online' } });
    const input = wrapper.find('input');
    expect(input.attributes('aria-describedby')).toBeUndefined();
    expect(wrapper.find('.field__hint').exists()).toBe(false);
    const labelId = input.attributes('aria-labelledby')!;
    expect(wrapper.find(`[id="${labelId}"]`).text()).toBe('Online');
  });

  it('gives every instance on a page its own ids', () => {
    const page = defineComponent({
      components: { ToggleSwitch },
      template: '<div><ToggleSwitch :model-value="false" label="A" hint="a" /><ToggleSwitch :model-value="true" label="B" hint="b" /></div>',
    });
    const [a, b] = mount(page).findAll('input');
    expect(a!.attributes('id')).not.toBe(b!.attributes('id'));
    expect(a!.attributes('aria-describedby')).not.toBe(b!.attributes('aria-describedby'));
  });

  it('emits the new state', async () => {
    const wrapper = mount(ToggleSwitch, { props: { modelValue: false, label: 'Online' } });
    await wrapper.find('input').setValue(true);
    expect(wrapper.emitted('update:modelValue')).toEqual([[true]]);
  });
});
