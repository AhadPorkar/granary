<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import ModalDialog from './ModalDialog.vue';

withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    message: string;
    confirmLabel?: string;
    danger?: boolean;
    busy?: boolean;
  }>(),
  { danger: false, busy: false, confirmLabel: undefined },
);

const emit = defineEmits<{ confirm: []; cancel: [] }>();
const { t } = useI18n();
</script>

<template>
  <ModalDialog :open="open" :title="title" @close="emit('cancel')">
    <p class="confirm__message">{{ message }}</p>
    <template #footer>
      <button type="button" class="btn" :disabled="busy" @click="emit('cancel')">{{ t('common.cancel') }}</button>
      <button
        type="button"
        class="btn"
        :class="danger ? 'btn--danger' : 'btn--primary'"
        :disabled="busy"
        data-testid="confirm-button"
        @click="emit('confirm')"
      >
        <span v-if="busy" class="spinner" aria-hidden="true"></span>
        {{ confirmLabel ?? t('common.confirm') }}
      </button>
    </template>
  </ModalDialog>
</template>

<style scoped>
.confirm__message {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
