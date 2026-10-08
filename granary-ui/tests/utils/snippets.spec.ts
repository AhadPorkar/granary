import { describe, expect, it } from 'vitest';
import { connectSnippets, npmRegistryKey, nugetIndexUrl } from '@/utils/snippets';
import { messages } from '@/i18n';
import { flatten } from '../helpers';

const URL = 'https://granary.example.com/repository/my-repo/';

describe('connect snippets', () => {
  it('Maven: settings.xml mirror + server credentials, distributionManagement and a deploy command', () => {
    const snippets = connectSnippets({ format: 'maven2', url: URL, name: 'my-repo', username: 'alice' });
    expect(snippets.map((s) => s.id)).toEqual(['maven-settings', 'maven-pom', 'maven-deploy']);
    const settings = snippets[0].code;
    expect(settings).toContain('<mirrorOf>*</mirrorOf>');
    expect(settings).toContain('<url>https://granary.example.com/repository/my-repo</url>');
    expect(settings).toContain('<username>alice</username>');
    expect(settings).toContain('<password><api-token></password>');
    expect(settings).toContain('<id>granary</id>');
    const pom = snippets[1].code;
    expect(pom).toContain('<distributionManagement>');
    expect(pom).toContain('<snapshotRepository>');
    expect(pom).toContain('<url>https://granary.example.com/repository/my-repo</url>');
    expect(snippets[2].code).toBe('mvn deploy');
  });

  it('npm: registry, .npmrc with _authToken, legacy login and publish', () => {
    const snippets = connectSnippets({ format: 'npm', url: URL, name: 'my-repo' });
    expect(snippets.map((s) => s.id)).toEqual(['npm-registry', 'npm-npmrc', 'npm-login', 'npm-publish']);
    expect(snippets[0].code).toBe('npm config set registry https://granary.example.com/repository/my-repo/');
    expect(snippets[1].code).toContain('registry=https://granary.example.com/repository/my-repo/');
    expect(snippets[1].code).toContain('//granary.example.com/repository/my-repo/:_authToken=<api-token>');
    expect(snippets[2].code).toBe('npm login --auth-type=legacy --registry https://granary.example.com/repository/my-repo/');
    expect(snippets[3].code).toBe('npm publish --registry https://granary.example.com/repository/my-repo/');
  });

  it('NuGet: add source, push with the API key and a nuget.config', () => {
    const snippets = connectSnippets({ format: 'nuget', url: URL, name: 'my-repo', username: 'bob', token: 'tok' });
    expect(snippets.map((s) => s.id)).toEqual(['nuget-source', 'nuget-push', 'nuget-config']);
    expect(snippets[0].code).toBe('dotnet nuget add source https://granary.example.com/repository/my-repo/index.json -n granary -u bob -p tok --store-password-in-clear-text');
    expect(snippets[1].code).toContain('dotnet nuget push');
    expect(snippets[1].code).toContain('-k tok -s https://granary.example.com/repository/my-repo/index.json');
    expect(snippets[2].code).toContain('<add key="granary" value="https://granary.example.com/repository/my-repo/index.json" />');
    expect(snippets[2].code).toContain('<add key="ClearTextPassword" value="tok" />');
  });

  it('normalises trailing slashes and derives npm/NuGet keys from the URL', () => {
    expect(npmRegistryKey('http://localhost:8081/repository/npm')).toBe('//localhost:8081/repository/npm/');
    expect(nugetIndexUrl('http://localhost:8081/repository/nuget///')).toBe('http://localhost:8081/repository/nuget/index.json');
    const maven = connectSnippets({ format: 'maven2', url: 'http://localhost:8081/repository/m2', name: 'm2' });
    expect(maven[0].code).toContain('<url>http://localhost:8081/repository/m2</url>');
  });

  it('uses placeholders when no username or token is known', () => {
    const [settings] = connectSnippets({ format: 'maven2', url: URL, name: 'my-repo' });
    expect(settings.code).toContain('<username><username></username>');
    expect(settings.code).toContain('<password><api-token></password>');
  });

  it('falls back to a plain URL snippet for an unknown format, and every title key exists', () => {
    const generic = connectSnippets({ format: 'cargo', url: URL, name: 'crates' });
    expect(generic).toHaveLength(1);
    expect(generic[0].code).toBe(URL);
    const keys = flatten(messages.en);
    for (const format of ['maven2', 'npm', 'nuget', 'cargo']) {
      for (const snippet of connectSnippets({ format, url: URL, name: 'x' })) {
        expect(keys[snippet.titleKey], snippet.titleKey).toBeTruthy();
      }
    }
  });
});
