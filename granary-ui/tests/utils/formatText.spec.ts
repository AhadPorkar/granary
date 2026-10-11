import { describe, expect, it } from 'vitest';
import type { FormatView, SettingDescriptor } from '@/api/types';
import { i18n, messages, SUPPORTED_LOCALES, type Locale } from '@/i18n';
import { formatNameKey, localizeFormat, localizeFormats, localizeSetting, settingTextKey, type Translator } from '@/utils/formatText';
import { flatten } from '../helpers';

const catalogue: Record<string, Record<string, string>> = {
  de: {
    'formatText.formats.go': 'Go-Module',
    'formatText.settings.helm.relativeUrls.label': 'Relative URLs',
    'formatText.settings.helm.relativeUrls.description': 'Diagramm-URLs relativ ausgeben.',
  },
};

function translator(locale: string): Translator {
  return {
    locale,
    t: (key) => catalogue[locale]?.[key] ?? key,
    te: (key, l) => key in (catalogue[l] ?? {}),
  };
}

const helmSettings: SettingDescriptor[] = [
  { key: 'helm.relativeUrls', label: 'Relative chart URLs', type: 'BOOLEAN', defaultValue: 'false', description: 'Write relative URLs.', appliesTo: ['HOSTED'] },
  { key: 'helm.newSetting', label: 'New setting', type: 'STRING', defaultValue: null, description: null, appliesTo: ['HOSTED'] },
];

const go: FormatView = { id: 'go', displayName: 'Go modules', types: ['PROXY', 'GROUP'], settings: [], templates: [] };
const helm: FormatView = { id: 'helm', displayName: 'Helm', types: ['HOSTED'], settings: helmSettings, templates: [] };

describe('formatText', () => {
  it('derives message keys from format ids and setting keys', () => {
    expect(formatNameKey('go')).toBe('formatText.formats.go');
    expect(settingTextKey('docker.rootRepository', 'label')).toBe('formatText.settings.docker.rootRepository.label');
    expect(settingTextKey('raw.metadataPatterns', 'description')).toBe('formatText.settings.raw.metadataPatterns.description');
  });

  it('refuses ids that are not plain dotted names', () => {
    expect(formatNameKey('')).toBeNull();
    expect(formatNameKey('a..b')).toBeNull();
    expect(formatNameKey('x{y}')).toBeNull();
    expect(settingTextKey('helm.relative urls', 'label')).toBeNull();
    expect(settingTextKey('helm.@linked', 'label')).toBeNull();
  });

  it('shows the server text in English even when a translation exists', () => {
    expect(localizeFormat(go, translator('en')).displayName).toBe('Go modules');
    expect(localizeSetting(helmSettings[0], translator('en')).label).toBe('Relative chart URLs');
  });

  it('translates names, labels and descriptions where the locale has them', () => {
    const [localGo, localHelm] = localizeFormats([go, helm], translator('de'));
    expect(localGo.displayName).toBe('Go-Module');
    expect(localHelm.displayName).toBe('Helm');
    expect(localHelm.settings[0]).toMatchObject({ key: 'helm.relativeUrls', label: 'Relative URLs', description: 'Diagramm-URLs relativ ausgeben.', type: 'BOOLEAN' });
  });

  it('falls back to the server text for settings the catalogue does not know yet', () => {
    const local = localizeFormat(helm, translator('de'));
    expect(local.settings[1]).toEqual(helmSettings[1]);
    expect(localizeFormat(go, translator('tr')).displayName).toBe('Go modules');
  });

  it('does not modify its input', () => {
    localizeFormats([go, helm], translator('de'));
    expect(go.displayName).toBe('Go modules');
    expect(helmSettings[0].label).toBe('Relative chart URLs');
  });

  it('works with the real message catalogue for every locale', () => {
    const real = (locale: Locale): Translator => ({
      locale,
      t: (key) => i18n.global.t(key, {}, { locale }),
      te: (key, l) => i18n.global.te(key, l as Locale),
    });
    const docker: FormatView = {
      id: 'docker',
      displayName: 'Docker',
      types: ['HOSTED'],
      settings: [{ key: 'docker.libraryPrefix', label: 'server label', type: 'BOOLEAN', defaultValue: 'true', description: 'server description', appliesTo: ['PROXY'] }],
      templates: [],
    };
    for (const locale of SUPPORTED_LOCALES) {
      const flat = flatten(messages[locale]);
      const setting = localizeFormat(docker, real(locale)).settings[0];
      if (locale === 'en') {
        expect(setting.label).toBe('server label');
      } else {
        expect(setting.label).toBe(flat['formatText.settings.docker.libraryPrefix.label']);
        expect(setting.description).toBe(flat['formatText.settings.docker.libraryPrefix.description']);
      }
    }
  });
});

describe('formatText catalogue', () => {
  const en = flatten(messages.en);
  const settingKeys = Object.keys(en).filter((k) => k.startsWith('formatText.settings.'));

  it('gives every setting both a label and a description', () => {
    const settings = new Set(settingKeys.map((k) => k.replace(/\.(label|description)$/, '')));
    expect(settings.size).toBeGreaterThanOrEqual(25);
    for (const setting of settings) {
      expect(en[`${setting}.label`], setting).toBeTruthy();
      expect(en[`${setting}.description`], setting).toBeTruthy();
    }
  });

  it('writes descriptions as full sentences in every locale', () => {
    for (const locale of SUPPORTED_LOCALES) {
      const flat = flatten(messages[locale]);
      const unfinished = settingKeys.filter((k) => k.endsWith('.description') && !/[.。!?\u061f]$/.test(flat[k].trim()));
      expect(unfinished, locale).toEqual([]);
    }
  });

  it('renders literal braces of URL templates through vue-i18n', () => {
    const description = i18n.global.t('formatText.settings.cargo.downloadUrl.description', {}, { locale: 'de' });
    expect(description).toContain('{crate}');
    expect(description).not.toContain("{'{'}");
  });
});
