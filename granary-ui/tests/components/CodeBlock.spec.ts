import { describe, expect, it } from 'vitest';
import CodeBlock from '@/components/CodeBlock.vue';
import { i18n } from '@/i18n';
import { mountWithPlugins } from '../helpers';

const code = '[global]\nindex-url = http://localhost:8081/repository/pypi-group/simple/\ntrusted-host = localhost';

describe('CodeBlock', () => {
  it('names the copy button after the snippet and keeps it out of the code', () => {
    const wrapper = mountWithPlugins(CodeBlock, { props: { code, title: 'pip configuration', filename: 'pip.conf', language: 'ini' } });
    const button = wrapper.find('.code-section__header button');
    expect(button.attributes('aria-label')).toBe('Copy “pip configuration”');
    expect(button.text()).toBe('Copy');
    expect(wrapper.find('pre button').exists()).toBe(false);
    expect(wrapper.find('pre').text()).toBe(code);
  });

  it('falls back to the file name, then to a generic label', () => {
    const named = mountWithPlugins(CodeBlock, { props: { code, filename: 'pip.conf' } });
    expect(named.find('button').attributes('aria-label')).toBe('Copy “pip.conf”');
    const plain = mountWithPlugins(CodeBlock, { props: { code } });
    expect(plain.find('button').attributes('aria-label')).toBe(i18n.global.t('common.copyToClipboard'));
  });

  it('keeps code left to right and scrollable by keyboard in a right-to-left UI', () => {
    i18n.global.locale.value = 'fa';
    const wrapper = mountWithPlugins(CodeBlock, { props: { code, title: 'pip', filename: '/etc/pip.conf' } });
    const pre = wrapper.find('pre');
    expect(pre.attributes('dir')).toBe('ltr');
    expect(pre.attributes('tabindex')).toBe('0');
    expect(wrapper.find('[data-testid="code-filename"]').classes()).toContain('ltr');
    expect(wrapper.find('h3 bdi').text()).toBe('pip');
    expect(wrapper.find('button').attributes('aria-label')).toContain('pip');
    expect(wrapper.find('button').attributes('aria-label')).not.toContain('Copy');
  });
});
