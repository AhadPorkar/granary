<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFormatters } from '@/composables/useFormatters';
import AppIcon from './AppIcon.vue';

const props = defineProps<{ page: number; size: number; total: number }>();
const emit = defineEmits<{ 'update:page': [page: number] }>();

const { t } = useI18n();
const fmt = useFormatters();

const pages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.size))));
const hasPrevious = computed(() => props.page > 0);
const hasNext = computed(() => props.page + 1 < pages.value);
</script>

<template>
  <nav class="pagination" :aria-label="t('common.pageOf', { page: page + 1, pages })">
    <span>{{ t('common.totalCount', { count: fmt.number(total) }) }}</span>
    <span class="cluster">
      <button type="button" class="btn btn--sm" :disabled="!hasPrevious" @click="emit('update:page', page - 1)">
        <AppIcon name="chevronLeft" :size="14" class="flip-rtl" />
        {{ t('common.previous') }}
      </button>
      <span class="num">{{ t('common.pageOf', { page: fmt.number(page + 1), pages: fmt.number(pages) }) }}</span>
      <button type="button" class="btn btn--sm" :disabled="!hasNext" @click="emit('update:page', page + 1)">
        {{ t('common.next') }}
        <AppIcon name="chevronRight" :size="14" class="flip-rtl" />
      </button>
    </span>
  </nav>
</template>

<style>
[dir='rtl'] .flip-rtl {
  transform: scaleX(-1);
}
</style>
