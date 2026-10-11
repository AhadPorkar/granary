<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api';
import type { AssetView, ComponentView, FormatView, RepositoryView } from '@/api/types';
import { REPOSITORIES_MANAGE, useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import { useToastStore } from '@/stores/toast';
import { useApiError } from '@/composables/useApiError';
import { useLocalizedFormats } from '@/composables/useLocalizedFormats';
import { useFormatters } from '@/composables/useFormatters';
import { anyNamespace, formatDisplayName } from '@/utils/repositories';
import { pathSegments } from '@/utils/format';
import AppIcon from '@/components/AppIcon.vue';
import AssetTable from '@/components/AssetTable.vue';
import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ConnectPanel from '@/components/ConnectPanel.vue';
import CopyButton from '@/components/CopyButton.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import PaginationBar from '@/components/PaginationBar.vue';
import StatusBadge from '@/components/StatusBadge.vue';

const props = defineProps<{ name: string }>();

const { t } = useI18n();
const auth = useAuthStore();
const system = useSystemStore();
const toast = useToastStore();
const apiError = useApiError();
const fmt = useFormatters();
const route = useRoute();
const router = useRouter();

const PAGE_SIZE = 25;

const repository = ref<RepositoryView | null>(null);
const rawFormats = ref<FormatView[]>([]);
const formats = useLocalizedFormats(rawFormats);
const loading = ref(true);
const error = ref<unknown>(null);
const busy = ref(false);

const tab = ref<'components' | 'connect'>(route.query.tab === 'connect' ? 'connect' : 'components');
const query = ref(typeof route.query.q === 'string' ? route.query.q : '');
const submittedQuery = ref(query.value);
const page = ref(0);
const components = ref<ComponentView[]>([]);
const total = ref(0);
const componentsLoading = ref(false);
const componentsError = ref<unknown>(null);

const expanded = reactive(new Set<string>());
const assetsByComponent = reactive(new Map<string, AssetView[]>());
const assetsLoading = reactive(new Set<string>());

const pendingDelete = ref<{ kind: 'repository' } | { kind: 'component'; component: ComponentView } | { kind: 'asset'; asset: AssetView; component: ComponentView } | null>(null);
const deleting = ref(false);

const canManage = computed(() => auth.can(REPOSITORIES_MANAGE));
const formatName = computed(() => (repository.value ? formatDisplayName(repository.value.format, formats.value) : ''));
const format = computed(() => formats.value.find((f) => f.id === repository.value?.format) ?? null);

/** The configured format settings with the label the format gives them, when it describes the key. */
const formatSettings = computed(() =>
  Object.entries(repository.value?.formatSettings ?? {}).map(([key, value]) => ({
    key,
    value,
    label: format.value?.settings.find((s) => s.key === key)?.label ?? null,
  })),
);
/** Formats without namespaces (raw, PyPI, Cargo, ...) would show a column of dashes. */
const showNamespace = computed(() => anyNamespace(components.value));
const columnCount = computed(() => 4 + (showNamespace.value ? 1 : 0) + (canManage.value ? 1 : 0));
let searchTimer: ReturnType<typeof setTimeout> | undefined;

async function loadFormats(): Promise<void> {
  try {
    rawFormats.value = await api.formats();
  } catch {
    /* the id is shown instead of the display name */
  }
}

async function loadRepository(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    repository.value = await api.repositories.get(props.name);
  } catch (e) {
    error.value = e;
    repository.value = null;
  } finally {
    loading.value = false;
  }
}

async function loadComponents(): Promise<void> {
  componentsLoading.value = true;
  componentsError.value = null;
  try {
    const result = await api.repositories.components(props.name, submittedQuery.value, page.value, PAGE_SIZE);
    components.value = result.items;
    total.value = result.total;
  } catch (e) {
    componentsError.value = e;
  } finally {
    componentsLoading.value = false;
  }
}

function search(): void {
  clearTimeout(searchTimer);
  submittedQuery.value = query.value.trim();
  page.value = 0;
  void router.replace({ query: { ...route.query, q: submittedQuery.value || undefined } });
  void loadComponents();
}

function onQueryInput(): void {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(search, 350);
}

function goToPage(next: number): void {
  page.value = next;
  void loadComponents();
}

async function toggleAssets(component: ComponentView): Promise<void> {
  if (expanded.has(component.id)) {
    expanded.delete(component.id);
    return;
  }
  expanded.add(component.id);
  if (!assetsByComponent.has(component.id)) {
    assetsLoading.add(component.id);
    try {
      assetsByComponent.set(component.id, await api.repositories.componentAssets(props.name, component.id));
    } catch (e) {
      toast.error(apiError.message(e));
      expanded.delete(component.id);
    } finally {
      assetsLoading.delete(component.id);
    }
  }
}

function componentLabel(c: ComponentView): string {
  return c.namespace ? `${c.namespace}:${c.name}` : c.name;
}

const confirmTitle = computed(() => {
  switch (pendingDelete.value?.kind) {
    case 'repository':
      return t('repositories.actions.delete');
    case 'component':
      return t('repository.deleteComponent');
    case 'asset':
      return t('repository.deleteAsset');
    default:
      return '';
  }
});

const confirmMessage = computed(() => {
  const p = pendingDelete.value;
  if (!p) {
    return '';
  }
  if (p.kind === 'repository') {
    return t('repositories.confirmDelete', { name: props.name });
  }
  if (p.kind === 'component') {
    return t('repository.confirmDeleteComponent', { name: componentLabel(p.component), version: p.component.version });
  }
  return t('repository.confirmDeleteAsset', { path: p.asset.path });
});

async function confirmDelete(): Promise<void> {
  const p = pendingDelete.value;
  if (!p) {
    return;
  }
  deleting.value = true;
  try {
    if (p.kind === 'repository') {
      await api.repositories.delete(props.name);
      toast.success(t('repositories.toasts.deleted', { name: props.name }));
      pendingDelete.value = null;
      await router.push({ name: 'repositories' });
      return;
    }
    if (p.kind === 'component') {
      const result = await api.repositories.deleteComponent(props.name, p.component.id);
      toast.success(t('repository.componentDeleted', { count: fmt.number(result.deletedAssets) }));
      assetsByComponent.delete(p.component.id);
      expanded.delete(p.component.id);
      await Promise.all([loadComponents(), loadRepository()]);
    } else {
      await api.repositories.deleteAsset(props.name, p.asset.id);
      toast.success(t('repository.assetDeleted'));
      assetsByComponent.set(p.component.id, (assetsByComponent.get(p.component.id) ?? []).filter((a) => a.id !== p.asset.id));
      await loadRepository();
    }
    pendingDelete.value = null;
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    deleting.value = false;
  }
}

async function toggleOffline(): Promise<void> {
  const repo = repository.value;
  if (!repo) {
    return;
  }
  busy.value = true;
  try {
    repository.value = await api.repositories.setOffline(repo.name, !repo.offlineMode);
    toast.success(t(repo.offlineMode ? 'repositories.toasts.online' : 'repositories.toasts.offline', { name: repo.name }));
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    busy.value = false;
  }
}

async function invalidate(): Promise<void> {
  const repo = repository.value;
  if (!repo) {
    return;
  }
  busy.value = true;
  try {
    repository.value = await api.repositories.invalidate(repo.name);
    toast.success(t('repositories.toasts.invalidated', { name: repo.name }));
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    busy.value = false;
  }
}

function selectTab(next: 'components' | 'connect'): void {
  tab.value = next;
  void router.replace({ query: { ...route.query, tab: next === 'components' ? undefined : next } });
}

watch(
  () => props.name,
  () => {
    expanded.clear();
    assetsByComponent.clear();
    page.value = 0;
    void Promise.all([loadRepository(), loadComponents()]);
  },
);

onMounted(() => {
  void system.loadInfo();
  void Promise.all([loadRepository(), loadComponents(), loadFormats()]);
});
</script>

<template>
  <div>
    <LoadingIndicator v-if="loading && !repository" />
    <ErrorAlert v-else-if="error" :error="error" />

    <template v-else-if="repository">
      <PageHeader :title="repository.name">
        <template v-if="repository.description" #subtitle><bdi>{{ repository.description }}</bdi></template>
        <template v-if="canManage">
          <button v-if="repository.type === 'PROXY'" type="button" class="btn" :disabled="busy" @click="toggleOffline">
            <AppIcon :name="repository.offlineMode ? 'cloud' : 'cloudOff'" :size="16" />
            {{ repository.offlineMode ? t('repositories.actions.setOnline') : t('repositories.actions.setOffline') }}
          </button>
          <button v-if="repository.type === 'PROXY'" type="button" class="btn" :disabled="busy" @click="invalidate">
            <AppIcon name="refresh" :size="16" />
            {{ t('repositories.actions.invalidate') }}
          </button>
          <RouterLink class="btn" :to="{ name: 'repository-edit', params: { name: repository.name } }">
            <AppIcon name="edit" :size="16" />
            {{ t('common.edit') }}
          </RouterLink>
          <button type="button" class="btn btn--danger" @click="pendingDelete = { kind: 'repository' }">
            <AppIcon name="trash" :size="16" />
            {{ t('common.delete') }}
          </button>
        </template>
      </PageHeader>

      <div class="card" style="margin-bottom: 1.25rem">
        <div class="card__body">
          <div class="cluster" style="margin-bottom: 0.75rem">
            <StatusBadge plain :title="repository.format" data-testid="format-name"><bdi>{{ formatName }}</bdi></StatusBadge>
            <StatusBadge plain variant="info">{{ t(`repositories.types.${repository.type}`) }}</StatusBadge>
            <StatusBadge :variant="repository.online ? 'success' : 'danger'">{{ repository.online ? t('common.online') : t('common.offline') }}</StatusBadge>
            <template v-if="repository.type === 'PROXY'">
              <StatusBadge v-if="repository.offlineMode" variant="warning">{{ t('repositories.upstream.offline') }}</StatusBadge>
              <StatusBadge v-else-if="repository.upstream?.reachable !== false" variant="success">{{ t('repositories.upstream.reachable') }}</StatusBadge>
              <StatusBadge v-else variant="danger">{{ t('repositories.upstream.unreachable') }}</StatusBadge>
            </template>
          </div>
          <dl class="kv">
            <dt>{{ t('repository.info.url') }}</dt>
            <dd>
              <span class="cluster" style="flex-wrap: nowrap">
                <code class="ltr">{{ repository.url }}</code>
                <CopyButton :text="repository.url" :label="t('repositories.actions.copyUrl')" />
              </span>
            </dd>
            <template v-if="repository.remoteUrl">
              <dt>{{ t('repository.info.remote') }}</dt>
              <dd><code class="ltr">{{ repository.remoteUrl }}</code></dd>
            </template>
            <template v-if="repository.upstream && !repository.upstream.reachable && repository.upstream.lastError">
              <dt>{{ t('dashboard.lastError') }}</dt>
              <dd style="color: var(--danger)"><bdi>{{ repository.upstream.lastError }}</bdi></dd>
            </template>
            <template v-if="repository.type === 'GROUP'">
              <dt>{{ t('repository.info.members') }}</dt>
              <dd class="cluster">
                <RouterLink v-for="member in repository.members" :key="member" :to="{ name: 'repository', params: { name: member } }">{{ member }}</RouterLink>
              </dd>
            </template>
            <dt>{{ t('repository.info.size') }}</dt>
            <dd>{{ fmt.bytes(repository.statistics?.bytes ?? 0) }} · {{ t('repositories.stats', { components: fmt.number(repository.statistics?.components ?? 0), assets: fmt.number(repository.statistics?.assets ?? 0) }) }}</dd>
            <dt>{{ t('repository.info.format') }}</dt>
            <dd><bdi>{{ formatName }}</bdi> <code v-if="formatName !== repository.format" class="small muted ltr">{{ repository.format }}</code></dd>
            <template v-if="formatSettings.length">
              <dt>{{ t('repository.info.formatSettings') }}</dt>
              <dd>
                <ul class="setting-list" data-testid="format-settings">
                  <li v-for="setting in formatSettings" :key="setting.key">
                    <code class="ltr" :title="setting.label ?? undefined">{{ setting.key }}</code>
                    <span aria-hidden="true">: </span>
                    <code class="ltr">{{ setting.value }}</code>
                    <span v-if="setting.label" class="muted small"> (<bdi>{{ setting.label }}</bdi>)</span>
                  </li>
                </ul>
              </dd>
            </template>
            <dt>{{ t('repository.info.blobStore') }}</dt>
            <dd>{{ repository.blobStore }}</dd>
            <dt>{{ t('repository.info.updated') }}</dt>
            <dd>{{ fmt.dateTime(repository.updatedAt) }}</dd>
          </dl>
        </div>
      </div>

      <div class="tabs" role="tablist">
        <button type="button" class="tabs__tab" role="tab" :aria-selected="tab === 'components'" @click="selectTab('components')">
          {{ t('repository.tabs.components') }}
        </button>
        <button type="button" class="tabs__tab" role="tab" :aria-selected="tab === 'connect'" @click="selectTab('connect')">
          {{ t('repository.tabs.connect') }}
        </button>
      </div>

      <section v-if="tab === 'components'" class="card" role="tabpanel">
        <div class="card__header">
          <form class="input-group" style="flex: 1; max-width: 30rem" role="search" @submit.prevent="search">
            <input v-model="query" class="input" type="search" :placeholder="t('repository.searchPlaceholder')" :aria-label="t('common.search')" @input="onQueryInput" />
            <button type="submit" class="btn btn--icon" :aria-label="t('common.search')">
              <AppIcon name="search" :size="18" />
            </button>
          </form>
          <span class="muted small">{{ t('common.totalCount', { count: fmt.number(total) }) }}</span>
        </div>

        <ErrorAlert :error="componentsError" />
        <LoadingIndicator v-if="componentsLoading && !components.length" />
        <EmptyState v-else-if="!components.length" :title="submittedQuery ? t('repository.noMatches', { q: submittedQuery }) : t('repository.noComponents')" />

        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th scope="col" style="width: 2.5rem"><span class="visually-hidden">{{ t('common.details') }}</span></th>
                <th v-if="showNamespace" scope="col" class="col-wide">{{ t('repository.columns.namespace') }}</th>
                <th scope="col">{{ t('repository.columns.name') }}</th>
                <th scope="col" class="col-wide">{{ t('repository.columns.version') }}</th>
                <th scope="col" class="col-wide">{{ t('repository.columns.updated') }}</th>
                <th v-if="canManage" scope="col" class="actions"><span class="visually-hidden">{{ t('common.actions') }}</span></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="component in components" :key="component.id">
                <tr>
                  <td>
                    <button type="button" class="expand-toggle" :aria-expanded="expanded.has(component.id)" :aria-label="expanded.has(component.id) ? t('repository.hideAssets') : t('repository.showAssets')" @click="toggleAssets(component)">
                      <AppIcon :name="expanded.has(component.id) ? 'chevronDown' : 'chevronRight'" :size="16" class="flip-rtl" />
                    </button>
                  </td>
                  <td v-if="showNamespace" class="component-cell col-wide"><code v-if="component.namespace" class="ltr">{{ component.namespace }}</code><span v-else class="faint">—</span></td>
                  <td class="component-cell">
                    <!-- on a phone the namespace and version columns are folded into this cell -->
                    <div v-if="showNamespace && component.namespace" class="narrow-only small muted"><code class="ltr">{{ component.namespace }}</code></div>
                    <strong class="ltr"><template v-for="(segment, i) in pathSegments(component.name)" :key="i"><wbr v-if="i" />{{ segment }}</template></strong>
                    <div class="narrow-only small" data-testid="component-version-inline"><span class="visually-hidden">{{ t('repository.columns.version') }}: </span><code class="ltr">{{ component.version }}</code></div>
                  </td>
                  <td class="component-cell col-wide"><code class="ltr">{{ component.version }}</code></td>
                  <td class="nowrap muted small col-wide">{{ fmt.dateTime(component.updatedAt) }}</td>
                  <td v-if="canManage" class="actions">
                    <button type="button" class="btn btn--danger-ghost btn--icon btn--sm" :aria-label="t('repository.deleteComponent')" :title="t('repository.deleteComponent')" @click="pendingDelete = { kind: 'component', component }">
                      <AppIcon name="trash" :size="15" />
                    </button>
                  </td>
                </tr>
                <tr v-if="expanded.has(component.id)" class="table__sub">
                  <td :colspan="columnCount">
                    <AssetTable :assets="assetsByComponent.get(component.id) ?? []" :loading="assetsLoading.has(component.id)" :can-delete="canManage" @delete="(asset) => (pendingDelete = { kind: 'asset', asset, component })" />
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <PaginationBar v-if="total > PAGE_SIZE" :page="page" :size="PAGE_SIZE" :total="total" @update:page="goToPage" />
      </section>

      <section v-else class="card" role="tabpanel">
        <div class="card__body">
          <ConnectPanel :repository="repository" :anonymous-read="system.info?.anonymousRead" />
        </div>
      </section>
    </template>

    <ConfirmDialog
      :open="pendingDelete !== null"
      :title="confirmTitle"
      :message="confirmMessage"
      :confirm-label="t('common.delete')"
      danger
      :busy="deleting"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </div>
</template>

<style scoped>
.component-cell {
  min-width: 8rem;
  overflow-wrap: break-word;
}

/* versions of raw-like formats are file names: long and without spaces */
.component-cell code {
  overflow-wrap: anywhere;
}

.narrow-only {
  display: none;
}

@media (max-width: 40rem) {
  .col-wide {
    display: none;
  }

  .narrow-only {
    display: block;
    margin-block: 0.15rem;
  }
}

.setting-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}
</style>
