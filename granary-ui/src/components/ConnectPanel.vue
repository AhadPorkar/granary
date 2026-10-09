<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { ConnectSnippet, RepositoryView } from '@/api/types';
import CodeBlock from './CodeBlock.vue';
import CopyButton from './CopyButton.vue';
import AppIcon from './AppIcon.vue';
import EmptyState from './EmptyState.vue';
import ErrorAlert from './ErrorAlert.vue';
import LoadingIndicator from './LoadingIndicator.vue';

/**
 * The "Connect" tab of a repository: the snippets come from the server, rendered by the repository's
 * format with the caller's username already filled in; the API token stays a placeholder.
 */
const props = defineProps<{ repository: RepositoryView; anonymousRead?: boolean }>();

const { t } = useI18n();

const snippets = ref<ConnectSnippet[]>([]);
const loading = ref(true);
const error = ref<unknown>(null);
let controller: AbortController | null = null;

async function load(name: string): Promise<void> {
  controller?.abort();
  const current = new AbortController();
  controller = current;
  loading.value = true;
  error.value = null;
  try {
    const result = await api.repositories.connect(name);
    if (!current.signal.aborted) {
      snippets.value = result;
    }
  } catch (e) {
    if (!current.signal.aborted) {
      snippets.value = [];
      error.value = e;
    }
  } finally {
    if (!current.signal.aborted) {
      loading.value = false;
    }
  }
}

watch(
  () => props.repository.name,
  (name) => {
    void load(name);
  },
  { immediate: true },
);

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div class="stack" data-testid="connect-panel">
    <div>
      <h2>{{ t('connect.title') }}</h2>
      <p class="muted" style="margin-top: 0.25rem">{{ t('connect.intro') }}</p>
      <p v-if="anonymousRead" class="muted small">{{ t('connect.anonymousHint') }}</p>
      <RouterLink class="btn btn--sm" :to="{ name: 'account' }">
        <AppIcon name="key" :size="14" />
        {{ t('connect.tokenLink') }}
      </RouterLink>
    </div>

    <div class="field">
      <span class="field__label">{{ t('repository.info.url') }}</span>
      <div class="input-group">
        <input class="input input--mono ltr" type="text" readonly :value="repository.url" :aria-label="t('repository.info.url')" />
        <CopyButton :text="repository.url" :label="t('repositories.actions.copyUrl')" :small="false" />
      </div>
    </div>

    <LoadingIndicator v-if="loading" />
    <template v-else>
      <ErrorAlert :error="error" />
      <EmptyState v-if="!error && !snippets.length" :title="t('connect.empty')" :text="t('connect.emptyHint')" />
      <CodeBlock
        v-for="snippet in snippets"
        :key="snippet.id"
        :code="snippet.code"
        :title="snippet.title"
        :filename="snippet.filename ?? null"
        :language="snippet.language"
        :data-snippet="snippet.id"
      />
    </template>
  </div>
</template>
