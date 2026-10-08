<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import EmptyState from '@/components/EmptyState.vue';

withDefaults(defineProps<{ kind?: 'notFound' | 'forbidden' }>(), { kind: 'notFound' });
const { t } = useI18n();
</script>

<template>
  <div class="card">
    <EmptyState :title="t(`${kind}.title`)" :text="t(`${kind}.text`)">
      <RouterLink class="btn btn--primary" :to="{ name: 'dashboard' }">{{ t('notFound.home') }}</RouterLink>
    </EmptyState>
  </div>
</template>
