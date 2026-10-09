<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api';
import type { FormatView, RepositoryView, StandardRepositoriesResult } from '@/api/types';
import { REPOSITORIES_MANAGE, useAuthStore } from '@/stores/auth';
import { useToastStore } from '@/stores/toast';
import { useApiError } from '@/composables/useApiError';
import { useFormatters } from '@/composables/useFormatters';
import { filterRepositories, formatDisplayName, formatOptions, groupByFormat } from '@/utils/repositories';
import AppIcon from '@/components/AppIcon.vue';
import ConfirmDialog from '@/components/ConfirmDialog.vue';
import CopyButton from '@/components/CopyButton.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatusBadge from '@/components/StatusBadge.vue';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const toast = useToastStore();
const apiError = useApiError();
const fmt = useFormatters();

const loading = ref(true);
const error = ref<unknown>(null);
const repositories = ref<RepositoryView[]>([]);
const formats = ref<FormatView[]>([]);
const busy = ref<string | null>(null);
const pendingDelete = ref<RepositoryView | null>(null);
const deleting = ref(false);

const query = ref('');
/** The dashboard links here with ?format=<id>; the selected format is kept in the URL so the link survives a reload. */
const formatFilter = ref(formatFromRoute());
const standardResult = ref<StandardRepositoriesResult | null>(null);

const canManage = computed(() => auth.can(REPOSITORIES_MANAGE));
const filtered = computed(() => filterRepositories(repositories.value, { query: query.value, format: formatFilter.value }));
/** Rows are grouped by format unless one format is selected, in which case a heading would only repeat the filter. */
const groups = computed(() => (formatFilter.value ? [{ format: formatFilter.value, displayName: formatDisplayName(formatFilter.value, formats.value), repositories: filtered.value }] : groupByFormat(filtered.value, formats.value)));
const grouped = computed(() => !formatFilter.value);
const availableFormats = computed(() => formatOptions(repositories.value, formats.value));
const filterActive = computed(() => query.value.trim() !== '' || formatFilter.value !== '');

function clearFilter(): void {
  query.value = '';
  formatFilter.value = '';
}

function formatFromRoute(): string {
  return typeof route.query.format === 'string' ? route.query.format : '';
}

watch(
  () => route.query.format,
  () => {
    formatFilter.value = formatFromRoute();
  },
);

watch(formatFilter, (format) => {
  if (format !== formatFromRoute()) {
    void router.replace({ query: { ...route.query, format: format || undefined } });
  }
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [repos, fmts] = await Promise.all([api.repositories.list(), api.formats()]);
    repositories.value = repos;
    formats.value = fmts;
  } catch (e) {
    error.value = e;
  } finally {
    loading.value = false;
  }
}

function replace(updated: RepositoryView): void {
  repositories.value = repositories.value.map((r) => (r.name === updated.name ? updated : r));
}

async function run(repo: RepositoryView, action: () => Promise<RepositoryView>, successKey: string): Promise<void> {
  busy.value = repo.name;
  try {
    replace(await action());
    toast.success(t(successKey, { name: repo.name }));
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    busy.value = null;
  }
}

function toggleOffline(repo: RepositoryView): Promise<void> {
  const offline = !repo.offlineMode;
  return run(repo, () => api.repositories.setOffline(repo.name, offline), offline ? 'repositories.toasts.offline' : 'repositories.toasts.online');
}

function invalidate(repo: RepositoryView): Promise<void> {
  return run(repo, () => api.repositories.invalidate(repo.name), 'repositories.toasts.invalidated');
}

async function confirmDelete(): Promise<void> {
  const repo = pendingDelete.value;
  if (!repo) {
    return;
  }
  deleting.value = true;
  try {
    await api.repositories.delete(repo.name);
    repositories.value = repositories.value.filter((r) => r.name !== repo.name);
    toast.success(t('repositories.toasts.deleted', { name: repo.name }));
    pendingDelete.value = null;
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    deleting.value = false;
  }
}

const creatingStandard = ref(false);

async function createStandard(): Promise<void> {
  creatingStandard.value = true;
  try {
    const result = await api.repositories.createStandard();
    standardResult.value = result;
    toast.success(
      result.created.length
        ? t('repositories.toasts.standardCreated', { count: result.created.length })
        : t('repositories.toasts.standardNone'),
    );
    await load();
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    creatingStandard.value = false;
  }
}

function typeVariant(type: RepositoryView['type']): 'info' | 'warning' | 'neutral' {
  return type === 'HOSTED' ? 'info' : type === 'PROXY' ? 'warning' : 'neutral';
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader :title="t('repositories.title')" :subtitle="t('repositories.subtitle')">
      <button type="button" class="btn" :disabled="loading" @click="load">
        <AppIcon name="refresh" :size="16" />
        {{ t('common.refresh') }}
      </button>
      <button
        v-if="canManage"
        type="button"
        class="btn"
        :disabled="creatingStandard"
        :title="t('repositories.standardHint')"
        data-testid="create-standard"
        @click="createStandard"
      >
        <AppIcon name="package" :size="16" />
        {{ t('repositories.standard') }}
      </button>
      <RouterLink v-if="canManage" class="btn btn--primary" :to="{ name: 'repository-new' }">
        <AppIcon name="plus" :size="16" />
        {{ t('repositories.new') }}
      </RouterLink>
    </PageHeader>

    <ErrorAlert :error="error" />
    <div v-if="standardResult" class="alert alert--info" role="status" data-testid="standard-result" style="margin-bottom: 1rem">
      <AppIcon class="alert__icon" name="package" :size="18" />
      <div class="alert__body">
        {{ standardResult.created.length ? t('repositories.standardCreatedList', { count: fmt.number(standardResult.created.length), names: standardResult.created.join(', ') }) : t('repositories.toasts.standardNone') }}
      </div>
      <button type="button" class="btn btn--ghost btn--icon btn--sm" :aria-label="t('common.close')" @click="standardResult = null">
        <AppIcon name="close" :size="16" />
      </button>
    </div>
    <LoadingIndicator v-if="loading" />

    <div v-else class="card">
      <EmptyState v-if="!repositories.length" :title="t('repositories.empty')" :text="canManage ? t('repositories.standardHint') : undefined">
        <button v-if="canManage" type="button" class="btn btn--primary" :disabled="creatingStandard" @click="createStandard">
          {{ t('repositories.standard') }}
        </button>
        <RouterLink v-if="canManage" class="btn" :to="{ name: 'repository-new' }">{{ t('repositories.new') }}</RouterLink>
      </EmptyState>

      <template v-else>
        <div class="card__header filter-bar" role="search">
          <div class="input-group filter-bar__search">
            <input
              v-model="query"
              class="input"
              type="search"
              :placeholder="t('repositories.filter.searchPlaceholder')"
              :aria-label="t('repositories.filter.search')"
              data-testid="filter-query"
            />
          </div>
          <label class="filter-bar__format">
            <span class="visually-hidden">{{ t('repositories.filter.format') }}</span>
            <select v-model="formatFilter" class="select" data-testid="filter-format">
              <option value="">{{ t('repositories.filter.allFormats') }}</option>
              <option v-for="f in availableFormats" :key="f.id" :value="f.id">{{ f.displayName }}</option>
            </select>
          </label>
          <span class="muted small filter-bar__count" aria-live="polite" data-testid="filter-count">
            {{ t('repositories.filter.count', { shown: fmt.number(filtered.length), total: fmt.number(repositories.length) }) }}
          </span>
          <button v-if="filterActive" type="button" class="btn btn--ghost btn--sm" data-testid="filter-clear" @click="clearFilter">
            <AppIcon name="close" :size="14" />
            {{ t('common.clear') }}
          </button>
        </div>

        <EmptyState v-if="!filtered.length" :title="t('repositories.filter.noMatches')" data-testid="filter-empty">
          <button type="button" class="btn" @click="clearFilter">{{ t('common.clear') }}</button>
        </EmptyState>

        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th scope="col">{{ t('repositories.columns.name') }}</th>
                <th scope="col">{{ t('repositories.columns.format') }}</th>
                <th scope="col">{{ t('repositories.columns.type') }}</th>
                <th scope="col">{{ t('repositories.columns.online') }}</th>
                <th scope="col">{{ t('repositories.columns.upstream') }}</th>
                <th scope="col">{{ t('repositories.columns.size') }}</th>
                <th scope="col">{{ t('repositories.columns.url') }}</th>
                <th scope="col" class="actions"><span class="visually-hidden">{{ t('common.actions') }}</span></th>
              </tr>
            </thead>
            <tbody v-for="group in groups" :key="group.format" :data-format-group="group.format">
              <tr v-if="grouped" class="table__group">
                <th scope="rowgroup" colspan="8" data-testid="format-heading">
                  <span class="cluster" style="gap: 0.5rem">
                    <span>{{ group.displayName }}</span>
                    <span class="badge badge--plain" :aria-label="t('common.totalCount', { count: fmt.number(group.repositories.length) })">{{ fmt.number(group.repositories.length) }}</span>
                  </span>
                </th>
              </tr>
              <tr v-for="repo in group.repositories" :key="repo.id" :data-repository="repo.name">
                <td>
                  <RouterLink :to="{ name: 'repository', params: { name: repo.name } }"><strong>{{ repo.name }}</strong></RouterLink>
                  <div v-if="repo.description" class="small muted truncate" style="max-width: 20rem">{{ repo.description }}</div>
                </td>
                <td>{{ group.displayName }}</td>
                <td><StatusBadge :variant="typeVariant(repo.type)" plain>{{ t(`repositories.types.${repo.type}`) }}</StatusBadge></td>
                <td>
                  <StatusBadge :variant="repo.online ? 'success' : 'danger'">{{ repo.online ? t('common.online') : t('common.offline') }}</StatusBadge>
                </td>
                <td>
                  <template v-if="repo.type === 'PROXY'">
                    <StatusBadge v-if="repo.offlineMode" variant="warning">{{ t('repositories.upstream.offline') }}</StatusBadge>
                    <StatusBadge v-else-if="repo.upstream?.reachable !== false" variant="success">{{ t('repositories.upstream.reachable') }}</StatusBadge>
                    <StatusBadge v-else variant="danger" :title="repo.upstream?.lastError ?? ''">{{ t('repositories.upstream.unreachable') }}</StatusBadge>
                  </template>
                  <span v-else class="faint">{{ t('repositories.upstream.notProxy') }}</span>
                </td>
                <td class="nowrap">
                  <div class="num">{{ fmt.bytes(repo.statistics?.bytes ?? 0) }}</div>
                  <div class="small muted">{{ t('repositories.stats', { components: fmt.number(repo.statistics?.components ?? 0), assets: fmt.number(repo.statistics?.assets ?? 0) }) }}</div>
                </td>
                <td>
                  <span class="cluster" style="flex-wrap: nowrap">
                    <code class="ltr truncate" style="max-width: 18rem" :title="repo.url">{{ repo.url }}</code>
                    <CopyButton :text="repo.url" :label="t('repositories.actions.copyUrl')" />
                  </span>
                </td>
                <td class="actions">
                  <span class="cluster">
                    <RouterLink class="btn btn--ghost btn--sm" :to="{ name: 'repository', params: { name: repo.name } }">{{ t('repositories.actions.browse') }}</RouterLink>
                    <template v-if="canManage">
                      <RouterLink class="btn btn--ghost btn--icon btn--sm" :to="{ name: 'repository-edit', params: { name: repo.name } }" :aria-label="t('repositories.actions.edit')" :title="t('repositories.actions.edit')">
                        <AppIcon name="edit" :size="15" />
                      </RouterLink>
                      <template v-if="repo.type === 'PROXY'">
                        <button type="button" class="btn btn--ghost btn--icon btn--sm" :disabled="busy === repo.name" :aria-label="repo.offlineMode ? t('repositories.actions.setOnline') : t('repositories.actions.setOffline')" :title="repo.offlineMode ? t('repositories.actions.setOnline') : t('repositories.actions.setOffline')" @click="toggleOffline(repo)">
                          <AppIcon :name="repo.offlineMode ? 'cloud' : 'cloudOff'" :size="15" />
                        </button>
                        <button type="button" class="btn btn--ghost btn--icon btn--sm" :disabled="busy === repo.name" :aria-label="t('repositories.actions.invalidate')" :title="t('repositories.actions.invalidate')" @click="invalidate(repo)">
                          <AppIcon name="refresh" :size="15" />
                        </button>
                      </template>
                      <button type="button" class="btn btn--danger-ghost btn--icon btn--sm" :aria-label="t('repositories.actions.delete')" :title="t('repositories.actions.delete')" @click="pendingDelete = repo">
                        <AppIcon name="trash" :size="15" />
                      </button>
                    </template>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <ConfirmDialog
      :open="pendingDelete !== null"
      :title="t('repositories.actions.delete')"
      :message="t('repositories.confirmDelete', { name: pendingDelete?.name ?? '' })"
      :confirm-label="t('common.delete')"
      danger
      :busy="deleting"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.6rem;
}

.filter-bar__search {
  flex: 1 1 14rem;
  max-width: 28rem;
}

.filter-bar__format {
  flex: 0 1 14rem;
  min-width: 10rem;
}

.filter-bar__count {
  margin-inline-start: auto;
}

.table__group th {
  background: var(--surface-2);
  font-size: 0.85rem;
  font-weight: 650;
  /* the column headers are upper-cased globally; a format keeps the spelling the server gives it */
  text-transform: none;
  letter-spacing: normal;
  color: var(--text-muted);
  padding-top: 0.55rem;
  padding-bottom: 0.55rem;
}
</style>
