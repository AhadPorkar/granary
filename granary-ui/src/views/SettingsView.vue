<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import { useSystemStore } from '@/stores/system';
import { useToastStore } from '@/stores/toast';
import ErrorAlert from '@/components/ErrorAlert.vue';
import FormField from '@/components/FormField.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import ToggleSwitch from '@/components/ToggleSwitch.vue';

const { t } = useI18n();
const system = useSystemStore();
const toast = useToastStore();

const loading = ref(true);
const saving = ref(false);
const error = ref<unknown>(null);
const saveError = ref<unknown>(null);
const form = reactive({ anonymousRead: true, offlineMode: false, baseUrl: '' });

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const settings = await api.system.settings();
    form.anonymousRead = settings.anonymousRead;
    form.offlineMode = settings.offlineMode;
    form.baseUrl = settings.baseUrl ?? '';
  } catch (e) {
    error.value = e;
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  saving.value = true;
  saveError.value = null;
  try {
    const saved = await api.system.updateSettings({
      anonymousRead: form.anonymousRead,
      offlineMode: form.offlineMode,
      baseUrl: form.baseUrl.trim(),
    });
    form.anonymousRead = saved.anonymousRead;
    form.offlineMode = saved.offlineMode;
    form.baseUrl = saved.baseUrl ?? '';
    toast.success(t('settings.saved'));
    void system.loadInfo(true);
  } catch (e) {
    saveError.value = e;
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader :title="t('settings.title')" :subtitle="t('settings.subtitle')" />
    <ErrorAlert :error="error" />
    <LoadingIndicator v-if="loading" />

    <form v-else class="card" novalidate @submit.prevent="save">
      <div class="card__body stack">
        <ErrorAlert :error="saveError" />
        <ToggleSwitch id="setting-anonymous" v-model="form.anonymousRead" :label="t('settings.anonymousRead')" :hint="t('settings.anonymousReadHint')" />
        <ToggleSwitch id="setting-offline" v-model="form.offlineMode" :label="t('settings.offlineMode')" :hint="t('settings.offlineModeHint')" />
        <FormField id="setting-base-url" :label="t('settings.baseUrl')" :hint="t('settings.baseUrlHint')" optional>
          <template #default="{ id, describedBy }">
            <input :id="id" v-model="form.baseUrl" class="input input--mono ltr" type="url" inputmode="url" :placeholder="t('settings.baseUrlPlaceholder')" :aria-describedby="describedBy" />
          </template>
        </FormField>
      </div>
      <div class="card__footer">
        <button type="submit" class="btn btn--primary" :disabled="saving">
          <span v-if="saving" class="spinner" aria-hidden="true"></span>
          {{ t('common.save') }}
        </button>
      </div>
    </form>
  </div>
</template>
