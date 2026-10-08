<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { RepositoryView } from '@/api/types';
import { useAuthStore } from '@/stores/auth';
import { connectSnippets } from '@/utils/snippets';
import CodeBlock from './CodeBlock.vue';
import CopyButton from './CopyButton.vue';
import AppIcon from './AppIcon.vue';

const props = defineProps<{ repository: RepositoryView; anonymousRead?: boolean }>();

const { t } = useI18n();
const auth = useAuthStore();

const snippets = computed(() =>
  connectSnippets({
    format: props.repository.format,
    url: props.repository.url,
    name: props.repository.name,
    username: auth.username ?? undefined,
  }),
);
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

    <CodeBlock v-for="snippet in snippets" :key="snippet.id" :code="snippet.code" :title="t(snippet.titleKey)" :filename="snippet.filename" />
  </div>
</template>
