<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
</script>

<template>
  <div class="loading" role="status">
    <span class="spinner" aria-hidden="true"></span>
    <span>{{ t('common.loading') }}</span>
  </div>
</template>
