import { describe, expect, it } from 'vitest';
import LanguageSwitcher from '@/components/LanguageSwitcher.vue';
import { LOCALE_STORAGE_KEY, i18n } from '@/i18n';
import { useLocaleStore } from '@/stores/locale';
import { mountWithPlugins } from '../helpers';

describe('LanguageSwitcher', () => {
  it('lists the five languages by their native names', () => {
    const wrapper = mountWithPlugins(LanguageSwitcher);
    const options = wrapper.findAll('option');
    // the last one is "Farsi" in Persian script
    expect(options.map((o) => o.text())).toEqual(['English', 'Deutsch', 'Français', 'Türkçe', '\u0641\u0627\u0631\u0633\u06cc']);
    expect(options.map((o) => o.attributes('value'))).toEqual(['en', 'de', 'fr', 'tr', 'fa']);
    expect(options.map((o) => o.attributes('lang'))).toEqual(['en', 'de', 'fr', 'tr', 'fa']);
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('en');
  });

  it('switches the locale, the document direction and persists the choice', async () => {
    const wrapper = mountWithPlugins(LanguageSwitcher);
    await wrapper.find('select').setValue('fa');
    expect(i18n.global.locale.value).toBe('fa');
    expect(document.documentElement.dir).toBe('rtl');
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('fa');
    expect(wrapper.find('.visually-hidden').text()).toBe(i18n.global.t('app.language'));

    await wrapper.find('select').setValue('tr');
    expect(i18n.global.locale.value).toBe('tr');
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('tr');
  });

  it('reflects a locale changed elsewhere', async () => {
    const wrapper = mountWithPlugins(LanguageSwitcher);
    useLocaleStore().set('de');
    await wrapper.vm.$nextTick();
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('de');
  });
});
