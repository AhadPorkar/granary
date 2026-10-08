<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api';
import type { ComponentView, RepositoryView } from '@/api/types';
import { useFormatters } from '@/composables/useFormatters';
import AppIcon from '@/components/AppIcon.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import PageHeader from '@/components/PageHeader.vue';
import PaginationBar from '@/components/PaginationBar.vue';

const { t } = useI18n();
const fmt = useFormatters();
const route = useRoute();
const router = useRouter();

const PAGE_SIZE = 25;

const query = ref(typeof route.query.q === 'string' ? route.query.q : '');
const repository = ref(typeof route.query.repository === 'string' ? route.query.repository : '');
const repositories = ref<RepositoryView[]>([]);
const results = ref<ComponentView[]>([]);
const total = ref(0);
const page = ref(0);
const loading = ref(false);
const searched = ref(false);
const error = ref<unknown>(null);
const lastQuery = ref('');
let timer: ReturnType<typeof setTimeout> | undefined;

async function loadRepositories(): Promise<void> {
  try {
    repositories.value = await api.repositories.list();
  } catch {
    /* the filter is optional */
  }
}

async function run(resetPage = true): Promise<void> {
  clearTimeout(timer);
  const q = query.value.trim();
  if (resetPage) {
    page.value = 0;
  }
  void router.replace({ query: { q: q || undefined, repository: repository.value || undefined } });
  if (!q) {
    results.value = [];
    total.value = 0;
    searched.value = false;
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const result = await api.search(q, repository.value || undefined, page.value, PAGE_SIZE);
    results.value = result.items;
    total.value = result.total;
    lastQuery.value = q;
    searched.value = true;
  } catch (e) {
    error.value = e;
  } finally {
    loading.value = false;
  }
}

function onInput(): void {
  clearTimeout(timer);
  timer = setTimeout(() => run(), 350);
}

function goToPage(next: number): void {
  page.value = next;
  void run(false);
}

watch(repository, () => run());

onMounted(() => {
  void loadRepositories();
  if (query.value) {
    void run();
  }
});
</script>

<template>
  <div>
    <PageHeader :title="t('search.title')" :subtitle="t('search.subtitle')" />

    <form class="card search-form" role="search" @submit.prevent="run()">
      <div class="card__body form-grid">
        <div class="field" style="grid-column: span 2">
          <label class="field__label" for="search-q">{{ t('common.search') }}</label>
          <div class="input-group">
            <input id="search-q" v-model="query" class="input" type="search" :placeholder="t('search.placeholder')" autofocus @input="onInput" />
            <button type="submit" class="btn btn--primary btn--icon" :aria-label="t('common.search')">
              <AppIcon name="search" :size="18" />
            </button>
          </div>
        </div>
        <div class="field">
          <label class="field__label" for="search-repository">{{ t('search.repository') }}</label>
          <select id="search-repository" v-model="repository" class="select">
            <option value="">{{ t('search.allRepositories') }}</option>
            <option v-for="repo in repositories" :key="repo.name" :value="repo.name">{{ repo.name }}</option>
          </select>
        </div>
      </div>
    </form>

    <div class="card" style="margin-top: 1rem">
      <ErrorAlert :error="error" />
      <LoadingIndicator v-if="loading && !results.length" />
      <EmptyState v-else-if="!searched" :title="t('search.prompt')" />
      <EmptyState v-else-if="!results.length" :title="t('search.empty', { q: lastQuery })" />
      <template v-else>
        <div class="card__header">
          <h2>{{ t('search.results', { count: fmt.number(total) }) }}</h2>
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th scope="col">{{ t('search.columns.repository') }}</th>
                <th scope="col">{{ t('repository.columns.namespace') }}</th>
                <th scope="col">{{ t('repository.columns.name') }}</th>
                <th scope="col">{{ t('repository.columns.version') }}</th>
                <th scope="col">{{ t('repository.columns.updated') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in results" :key="item.id">
                <td>
                  <RouterLink :to="{ name: 'repository', params: { name: item.repository }, query: { q: item.name } }">{{ item.repository }}</RouterLink>
                  <span class="faint small"> · {{ item.format }}</span>
                </td>
                <td><code v-if="item.namespace" class="ltr">{{ item.namespace }}</code><span v-else class="faint">—</span></td>
                <td><strong class="ltr">{{ item.name }}</strong></td>
                <td><code class="ltr">{{ item.version }}</code></td>
                <td class="nowrap muted small">{{ fmt.dateTime(item.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <PaginationBar v-if="total > PAGE_SIZE" :page="page" :size="PAGE_SIZE" :total="total" @update:page="goToPage" />
      </template>
    </div>
  </div>
</template>

<style scoped>
@media (max-width: 40rem) {
  .search-form .field[style] {
    grid-column: span 1 !important;
  }
}
</style>
