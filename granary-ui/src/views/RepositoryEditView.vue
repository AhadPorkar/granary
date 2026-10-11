<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { api } from '@/api';
import { useLocalizedFormats } from '@/composables/useLocalizedFormats';
import type { FormatView, RepositoryRequest, RepositoryView } from '@/api/types';
import { useToastStore } from '@/stores/toast';
import ErrorAlert from '@/components/ErrorAlert.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import RepositoryForm from '@/components/RepositoryForm.vue';

const props = defineProps<{ name?: string }>();

const { t } = useI18n();
const router = useRouter();
const toast = useToastStore();

const loading = ref(true);
const loadError = ref<unknown>(null);
const saveError = ref<unknown>(null);
const saving = ref(false);
const rawFormats = ref<FormatView[]>([]);
const formats = useLocalizedFormats(rawFormats);
const repositories = ref<RepositoryView[]>([]);
const initial = ref<RepositoryView | null>(null);

const mode = computed<'create' | 'edit'>(() => (props.name ? 'edit' : 'create'));
const title = computed(() => (props.name ? t('repositories.editName', { name: props.name }) : t('repositories.new')));

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = null;
  try {
    const [fmts, repos] = await Promise.all([api.formats(), api.repositories.list()]);
    rawFormats.value = fmts;
    repositories.value = repos;
    initial.value = props.name ? (repos.find((r) => r.name === props.name) ?? (await api.repositories.get(props.name))) : null;
  } catch (e) {
    loadError.value = e;
  } finally {
    loading.value = false;
  }
}

async function save(request: RepositoryRequest): Promise<void> {
  saving.value = true;
  saveError.value = null;
  try {
    const saved = props.name ? await api.repositories.update(props.name, request) : await api.repositories.create(request);
    toast.success(t(props.name ? 'repositories.toasts.updated' : 'repositories.toasts.created', { name: saved.name }));
    await router.push({ name: 'repository', params: { name: saved.name } });
  } catch (e) {
    saveError.value = e;
  } finally {
    saving.value = false;
  }
}

function cancel(): void {
  void router.push(props.name ? { name: 'repository', params: { name: props.name } } : { name: 'repositories' });
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader :title="title" />
    <ErrorAlert :error="loadError" />
    <LoadingIndicator v-if="loading" />
    <div v-else-if="!loadError" class="card">
      <div class="card__body">
        <RepositoryForm :mode="mode" :initial="initial" :formats="formats" :repositories="repositories" :error="saveError" :saving="saving" @submit="save" @cancel="cancel" />
      </div>
    </div>
  </div>
</template>
