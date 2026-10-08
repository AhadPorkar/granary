<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { useToastStore } from '@/stores/toast';
import AppIcon from './AppIcon.vue';

const { t } = useI18n();
const toast = useToastStore();
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <div v-for="item in toast.toasts" :key="item.id" class="toast" :class="`toast--${item.kind}`" role="status">
      <AppIcon :name="item.kind === 'success' ? 'checkCircle' : item.kind === 'error' ? 'alert' : 'info'" :size="16" />
      <span>{{ item.message }}</span>
      <button type="button" class="toast__close" :aria-label="t('common.close')" @click="toast.dismiss(item.id)">
        <AppIcon name="close" :size="14" />
      </button>
    </div>
  </div>
</template>
