<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { FormatView, RepositoryView } from '@/api/types';
import { REPOSITORIES_MANAGE, useAuthStore } from '@/stores/auth';
import { useToastStore } from '@/stores/toast';
import { useApiError } from '@/composables/useApiError';
import { useFormatters } from '@/composables/useFormatters';
import AppIcon from '@/components/AppIcon.vue';
import ConfirmDialog from '@/components/ConfirmDialog.vue';
import CopyButton from '@/components/CopyButton.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatusBadge from '@/components/StatusBadge.vue';

const { t } = useI18n();
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

const canManage = computed(() => auth.can(REPOSITORIES_MANAGE));
const formatNames = computed(() => Object.fromEntries(formats.value.map((f) => [f.id, f.displayName])));

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
      <RouterLink v-if="canManage" class="btn btn--primary" :to="{ name: 'repository-new' }">
        <AppIcon name="plus" :size="16" />
        {{ t('repositories.new') }}
      </RouterLink>
    </PageHeader>

    <ErrorAlert :error="error" />
    <LoadingIndicator v-if="loading" />

    <div v-else class="card">
      <EmptyState v-if="!repositories.length" :title="t('repositories.empty')" :text="canManage ? t('repositories.emptyHint') : undefined">
        <RouterLink v-if="canManage" class="btn btn--primary" :to="{ name: 'repository-new' }">{{ t('repositories.new') }}</RouterLink>
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
          <tbody>
            <tr v-for="repo in repositories" :key="repo.id" :data-repository="repo.name">
              <td>
                <RouterLink :to="{ name: 'repository', params: { name: repo.name } }"><strong>{{ repo.name }}</strong></RouterLink>
                <div v-if="repo.description" class="small muted truncate" style="max-width: 20rem">{{ repo.description }}</div>
              </td>
              <td>{{ formatNames[repo.format] ?? repo.format }}</td>
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
