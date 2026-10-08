<script setup lang="ts">
import { computed } from 'vue';
import { useApiError } from '@/composables/useApiError';
import AppIcon from './AppIcon.vue';

const props = withDefaults(defineProps<{ error: unknown; variant?: 'danger' | 'warning' | 'info' | 'success'; title?: string }>(), {
  variant: 'danger',
  title: undefined,
});

const apiError = useApiError();
const message = computed(() => (props.error ? apiError.message(props.error) : ''));
</script>

<template>
  <div v-if="error" class="alert" :class="`alert--${variant}`" role="alert">
    <AppIcon class="alert__icon" :name="variant === 'danger' ? 'alert' : 'info'" :size="18" />
    <div class="alert__body">
      <div v-if="title" class="alert__title">{{ title }}</div>
      <div>{{ message }}</div>
    </div>
  </div>
</template>
