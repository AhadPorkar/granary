<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import type { AssetView } from '@/api/types';
import { useFormatters } from '@/composables/useFormatters';
import AppIcon from './AppIcon.vue';
import CopyButton from './CopyButton.vue';
import StatusBadge from './StatusBadge.vue';

defineProps<{ assets: AssetView[]; canDelete: boolean; loading?: boolean }>();
const emit = defineEmits<{ delete: [asset: AssetView] }>();

const { t } = useI18n();
const fmt = useFormatters();

function originVariant(origin: AssetView['origin']): 'success' | 'info' | 'neutral' {
  return origin === 'UPLOADED' ? 'success' : origin === 'CACHED' ? 'info' : 'neutral';
}
</script>

<template>
  <div v-if="loading" class="loading small"><span class="spinner" aria-hidden="true"></span>{{ t('common.loading') }}</div>
  <p v-else-if="!assets.length" class="muted small" style="margin: 0.5rem 0">{{ t('repository.assets.empty') }}</p>
  <div v-else class="table-wrap">
    <table class="table table--nested">
      <thead>
        <tr>
          <th scope="col">{{ t('repository.assets.path') }}</th>
          <th scope="col">{{ t('repository.assets.size') }}</th>
          <th scope="col">{{ t('repository.assets.sha256') }}</th>
          <th scope="col">{{ t('repository.assets.downloads') }}</th>
          <th scope="col">{{ t('repository.assets.origin') }}</th>
          <th v-if="canDelete" scope="col" class="actions"><span class="visually-hidden">{{ t('common.actions') }}</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="asset in assets" :key="asset.id">
          <td>
            <code class="ltr">{{ asset.path }}</code>
            <StatusBadge v-if="asset.metadata" plain class="small" style="margin-inline-start: 0.4rem">{{ t('repository.assets.metadata') }}</StatusBadge>
          </td>
          <td class="num nowrap">{{ fmt.bytes(asset.size) }}</td>
          <td>
            <span v-if="asset.sha256" class="cluster" style="flex-wrap: nowrap">
              <code class="ltr" :title="asset.sha256">{{ asset.sha256.slice(0, 12) }}…</code>
              <CopyButton :text="asset.sha256" />
            </span>
            <span v-else class="faint">{{ t('common.notAvailable') }}</span>
          </td>
          <td class="num">{{ fmt.number(asset.downloadCount) }}</td>
          <td><StatusBadge :variant="originVariant(asset.origin)">{{ t(`repository.origins.${asset.origin}`) }}</StatusBadge></td>
          <td v-if="canDelete" class="actions">
            <button type="button" class="btn btn--danger-ghost btn--icon btn--sm" :aria-label="t('repository.deleteAsset')" :title="t('repository.deleteAsset')" @click="emit('delete', asset)">
              <AppIcon name="trash" :size="15" />
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
