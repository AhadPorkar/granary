/**
 * Ready-to-paste configuration for the package managers of each format. The code is never translated;
 * only the titles are (through {@link Snippet.titleKey}).
 */

export interface Snippet {
  id: string;
  titleKey: string;
  language: 'xml' | 'shell' | 'ini';
  filename?: string;
  code: string;
}

export interface SnippetContext {
  format: string;
  /** Repository URL as reported by the server, e.g. https://host/repository/name/ */
  url: string;
  name: string;
  username?: string;
  token?: string;
}

export const DEFAULT_USERNAME_PLACEHOLDER = '<username>';
export const DEFAULT_TOKEN_PLACEHOLDER = '<api-token>';

function withoutTrailingSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

function withTrailingSlash(url: string): string {
  return `${withoutTrailingSlash(url)}/`;
}

/** npm keys credentials by the registry URL without its scheme: //host/path/:_authToken=… */
export function npmRegistryKey(url: string): string {
  return withTrailingSlash(url).replace(/^https?:/, '');
}

export function nugetIndexUrl(url: string): string {
  return `${withTrailingSlash(url)}index.json`;
}

export function mavenSnippets(ctx: SnippetContext, username: string, token: string): Snippet[] {
  const url = withoutTrailingSlash(ctx.url);
  return [
    {
      id: 'maven-settings',
      titleKey: 'connect.maven.settings',
      language: 'xml',
      filename: '~/.m2/settings.xml',
      code: `<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors>
    <mirror>
      <id>granary</id>
      <name>Granary ${ctx.name}</name>
      <mirrorOf>*</mirrorOf>
      <url>${url}</url>
    </mirror>
  </mirrors>
  <servers>
    <server>
      <id>granary</id>
      <username>${username}</username>
      <password>${token}</password>
    </server>
  </servers>
</settings>`,
    },
    {
      id: 'maven-pom',
      titleKey: 'connect.maven.pom',
      language: 'xml',
      filename: 'pom.xml',
      code: `<distributionManagement>
  <repository>
    <id>granary</id>
    <url>${url}</url>
  </repository>
  <snapshotRepository>
    <id>granary</id>
    <url>${url}</url>
  </snapshotRepository>
</distributionManagement>`,
    },
    {
      id: 'maven-deploy',
      titleKey: 'connect.maven.deploy',
      language: 'shell',
      code: 'mvn deploy',
    },
  ];
}

export function npmSnippets(ctx: SnippetContext, token: string): Snippet[] {
  const registry = withTrailingSlash(ctx.url);
  return [
    {
      id: 'npm-registry',
      titleKey: 'connect.npm.registry',
      language: 'shell',
      code: `npm config set registry ${registry}`,
    },
    {
      id: 'npm-npmrc',
      titleKey: 'connect.npm.npmrc',
      language: 'ini',
      filename: '.npmrc',
      code: `registry=${registry}
${npmRegistryKey(ctx.url)}:_authToken=${token}`,
    },
    {
      id: 'npm-login',
      titleKey: 'connect.npm.login',
      language: 'shell',
      code: `npm login --auth-type=legacy --registry ${registry}`,
    },
    {
      id: 'npm-publish',
      titleKey: 'connect.npm.publish',
      language: 'shell',
      code: `npm publish --registry ${registry}`,
    },
  ];
}

export function nugetSnippets(ctx: SnippetContext, username: string, token: string): Snippet[] {
  const index = nugetIndexUrl(ctx.url);
  return [
    {
      id: 'nuget-source',
      titleKey: 'connect.nuget.source',
      language: 'shell',
      code: `dotnet nuget add source ${index} -n granary -u ${username} -p ${token} --store-password-in-clear-text`,
    },
    {
      id: 'nuget-push',
      titleKey: 'connect.nuget.push',
      language: 'shell',
      code: `dotnet nuget push ./MyPackage.1.0.0.nupkg -k ${token} -s ${index}`,
    },
    {
      id: 'nuget-config',
      titleKey: 'connect.nuget.config',
      language: 'xml',
      filename: 'nuget.config',
      code: `<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="granary" value="${index}" />
  </packageSources>
  <packageSourceCredentials>
    <granary>
      <add key="Username" value="${username}" />
      <add key="ClearTextPassword" value="${token}" />
    </granary>
  </packageSourceCredentials>
</configuration>`,
    },
  ];
}

export function connectSnippets(ctx: SnippetContext): Snippet[] {
  const username = ctx.username || DEFAULT_USERNAME_PLACEHOLDER;
  const token = ctx.token || DEFAULT_TOKEN_PLACEHOLDER;
  switch (ctx.format) {
    case 'maven2':
    case 'maven':
      return mavenSnippets(ctx, username, token);
    case 'npm':
      return npmSnippets(ctx, token);
    case 'nuget':
      return nugetSnippets(ctx, username, token);
    default:
      return [
        {
          id: 'generic-url',
          titleKey: 'connect.generic.url',
          language: 'shell',
          code: withTrailingSlash(ctx.url),
        },
      ];
  }
}
