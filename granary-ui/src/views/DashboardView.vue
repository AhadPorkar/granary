<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { FormatView, RepositoryView, SystemStatus } from '@/api/types';
import { REPOSITORIES_MANAGE, SETTINGS_MANAGE, useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import { useToastStore } from '@/stores/toast';
import { useApiError } from '@/composables/useApiError';
import { useFormatters } from '@/composables/useFormatters';
import { countByFormat } from '@/utils/repositories';
import AppIcon from '@/components/AppIcon.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import ToggleSwitch from '@/components/ToggleSwitch.vue';

const { t } = useI18n();
const auth = useAuthStore();
const system = useSystemStore();
const toast = useToastStore();
const apiError = useApiError();
const fmt = useFormatters();

const loading = ref(true);
const error = ref<unknown>(null);
const status = ref<SystemStatus | null>(null);
const repositories = ref<RepositoryView[]>([]);
const formats = ref<FormatView[]>([]);
const togglingOffline = ref(false);

const seesStatus = computed(() => auth.can(SETTINGS_MANAGE) || auth.can(REPOSITORIES_MANAGE));
const canToggleOffline = computed(() => auth.can(SETTINGS_MANAGE));

const repositoryCount = computed(() => status.value?.repositories ?? repositories.value.length);
const byFormat = computed(() => countByFormat(repositories.value, formats.value));
const componentCount = computed(() => repositories.value.reduce((sum, r) => sum + (r.statistics?.components ?? 0), 0));
const assetCount = computed(() => repositories.value.reduce((sum, r) => sum + (r.statistics?.assets ?? 0), 0));
const totalBytes = computed(() => repositories.value.reduce((sum, r) => sum + (r.statistics?.bytes ?? 0), 0));
const blobCount = computed(() => status.value?.blobStores.reduce((sum, s) => sum + s.blobCount, 0) ?? 0);
const offlineMode = computed(() => status.value?.offlineMode ?? system.info?.offlineMode ?? false);
const jvmUsed = computed(() => (status.value ? status.value.jvm.totalMemory - status.value.jvm.freeMemory : 0));

function usage(store: { totalBytes: number; availableBytes: number }): number {
  const capacity = store.totalBytes + store.availableBytes;
  return capacity > 0 ? store.totalBytes / capacity : 0;
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [repos, stat, fmts] = await Promise.all([
      api.repositories.list(),
      seesStatus.value ? api.system.status() : Promise.resolve(null),
      api.formats().catch(() => [] as FormatView[]),
    ]);
    repositories.value = repos;
    status.value = stat;
    formats.value = fmts;
  } catch (e) {
    error.value = e;
  } finally {
    loading.value = false;
  }
}

async function setOffline(value: boolean): Promise<void> {
  togglingOffline.value = true;
  try {
    await api.system.updateSettings({ offlineMode: value });
    await Promise.all([system.loadInfo(true), load()]);
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    togglingOffline.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader :title="t('dashboard.title')" :subtitle="t('dashboard.subtitle')">
      <button type="button" class="btn" :disabled="loading" @click="load">
        <AppIcon name="refresh" :size="16" />
        {{ t('common.refresh') }}
      </button>
    </PageHeader>

    <div class="stack">
      <div v-if="status?.defaultAdminPassword" class="alert alert--warning" role="alert" data-testid="default-password-banner">
        <AppIcon class="alert__icon" name="alert" :size="20" />
        <div class="alert__body">
          <div class="alert__title">{{ t('dashboard.defaultPasswordTitle') }}</div>
          <div>{{ t('dashboard.defaultPasswordText') }}</div>
          <RouterLink class="banner-link" :to="{ name: 'account' }" style="display: inline-block; margin-top: 0.4rem">
            {{ t('dashboard.defaultPasswordAction') }} →
          </RouterLink>
        </div>
      </div>

      <div v-if="offlineMode" class="alert alert--info" role="status">
        <AppIcon class="alert__icon" name="cloudOff" :size="18" />
        <div class="alert__body">{{ t('dashboard.offlineActive') }}</div>
      </div>

      <ErrorAlert :error="error" />
      <LoadingIndicator v-if="loading && !repositories.length" />

      <div v-else class="grid">
        <RouterLink class="card stat stat--link" :to="{ name: 'repositories' }">
          <span class="stat__label">{{ t('dashboard.repositories') }}</span>
          <span class="stat__value">{{ fmt.number(repositoryCount) }}</span>
        </RouterLink>
        <div class="card stat">
          <span class="stat__label">{{ t('dashboard.components') }}</span>
          <span class="stat__value">{{ fmt.number(componentCount) }}</span>
          <span class="stat__detail">{{ t('dashboard.assetCount', { count: fmt.number(assetCount) }) }}</span>
        </div>
        <div class="card stat">
          <span class="stat__label">{{ t('dashboard.storage') }}</span>
          <span class="stat__value">{{ fmt.bytes(totalBytes) }}</span>
          <span v-if="status" class="stat__detail">{{ t('dashboard.blobStoreBlobs', { count: fmt.number(blobCount) }) }}</span>
        </div>
        <div v-if="status" class="card stat">
          <span class="stat__label">{{ t('dashboard.version') }}</span>
          <span class="stat__value" style="font-size: 1.25rem">{{ status.version }}</span>
          <span class="stat__detail">{{ t('dashboard.jvm') }} {{ status.jvm.version }}</span>
        </div>
      </div>

      <section v-if="byFormat.length" class="card" data-testid="by-format">
        <div class="card__header"><h2>{{ t('dashboard.byFormat') }}</h2></div>
        <ul class="format-counts">
          <li v-for="entry in byFormat" :key="entry.format" class="format-counts__item">
            <RouterLink :to="{ name: 'repositories', query: { format: entry.format } }">{{ entry.displayName }}</RouterLink>
            <span class="format-counts__value">{{ fmt.number(entry.count) }}</span>
          </li>
        </ul>
      </section>

      <div v-if="status" class="grid grid--2">
        <section class="card">
          <div class="card__header"><h2>{{ t('dashboard.blobStores') }}</h2></div>
          <div class="card__body stack">
            <div v-for="store in status.blobStores" :key="store.name" class="stack stack--tight">
              <div class="cluster" style="justify-content: space-between">
                <strong>{{ store.name }}</strong>
                <span class="muted small">{{ t('dashboard.blobStoreBlobs', { count: fmt.number(store.blobCount) }) }}</span>
              </div>
              <div class="meter" role="meter" :aria-valuenow="Math.round(usage(store) * 100)" aria-valuemin="0" aria-valuemax="100" :aria-label="store.name">
                <div class="meter__fill" :style="{ width: `${Math.max(1, Math.round(usage(store) * 100))}%` }"></div>
              </div>
              <div class="cluster small muted" style="justify-content: space-between">
                <span>{{ t('dashboard.blobStoreUsed', { used: fmt.bytes(store.totalBytes) }) }}</span>
                <span>{{ t('dashboard.blobStoreAvailable', { available: fmt.bytes(store.availableBytes) }) }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card__header">
            <h2>{{ t('dashboard.upstreams') }}</h2>
            <ToggleSwitch
              v-if="canToggleOffline"
              id="global-offline"
              :model-value="offlineMode"
              :label="t('dashboard.offlineMode')"
              :disabled="togglingOffline"
              @update:model-value="setOffline"
            />
          </div>
          <p v-if="canToggleOffline" class="field__hint" style="padding: 0.5rem 1rem 0">{{ t('dashboard.offlineModeHint') }}</p>
          <ul v-if="status.upstreams.length" class="upstream-list">
            <li v-for="up in status.upstreams" :key="up.repository" class="upstream-list__item">
              <div class="truncate">
                <RouterLink :to="{ name: 'repository', params: { name: up.repository } }"><strong>{{ up.repository }}</strong></RouterLink>
                <div class="small muted truncate ltr" :title="up.remoteUrl">{{ up.remoteUrl }}</div>
              </div>
              <div class="cluster">
                <StatusBadge v-if="up.offlineMode || offlineMode" variant="warning">{{ t('common.offline') }}</StatusBadge>
                <StatusBadge v-else-if="up.status.reachable" variant="success">{{ t('dashboard.reachable') }}</StatusBadge>
                <StatusBadge v-else variant="danger">{{ t('dashboard.unreachable') }}</StatusBadge>
              </div>
              <div v-if="!up.status.reachable" class="upstream-list__error">
                <span v-if="up.status.consecutiveFailures">{{ t('dashboard.failures', { count: fmt.number(up.status.consecutiveFailures) }) }} · </span>
                <span v-if="up.status.lastError">{{ t('dashboard.lastError') }}: {{ up.status.lastError }}</span>
                <span v-if="up.status.retryAt"> · {{ t('dashboard.retryAt') }}: {{ fmt.relative(up.status.retryAt) }}</span>
              </div>
              <div v-else-if="up.status.lastSuccessAt" class="small faint" style="grid-column: 1 / -1">
                {{ t('dashboard.lastSuccess') }}: {{ fmt.relative(up.status.lastSuccessAt) }}
              </div>
            </li>
          </ul>
          <p v-else class="empty">{{ t('dashboard.upstreamsEmpty') }}</p>
        </section>

        <section class="card">
          <div class="card__header"><h2>{{ t('dashboard.system') }}</h2></div>
          <div class="card__body">
            <dl class="kv">
              <dt>{{ t('dashboard.version') }}</dt>
              <dd>{{ status.version }}</dd>
              <dt>{{ t('dashboard.jvm') }}</dt>
              <dd>{{ status.jvm.version }}</dd>
              <dt>{{ t('dashboard.memory') }}</dt>
              <dd>{{ t('dashboard.memoryDetail', { used: fmt.bytes(jvmUsed), max: fmt.bytes(status.jvm.maxMemory) }) }}</dd>
              <dt>{{ t('dashboard.processors') }}</dt>
              <dd>{{ fmt.number(status.jvm.processors) }}</dd>
              <dt>{{ t('settings.anonymousRead') }}</dt>
              <dd>{{ status.anonymousRead ? t('common.enabled') : t('common.disabled') }}</dd>
            </dl>
          </div>
        </section>
      </div>

      <p v-else-if="!loading" class="muted small">{{ t('dashboard.limited') }}</p>
    </div>
  </div>
</template>

<style scoped>
.format-counts {
  list-style: none;
  margin: 0;
  padding: 0.5rem 1rem 0.75rem;
  display: grid;
  gap: 0.25rem 1.5rem;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 12rem), 1fr));
}

.format-counts__item {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.3rem 0;
  border-bottom: 1px solid var(--border);
  font-size: 0.875rem;
}

.format-counts__value {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.stat--link {
  color: inherit;
}

.stat--link:hover {
  text-decoration: none;
  border-color: var(--primary);
}
</style>
