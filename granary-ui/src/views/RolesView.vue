<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { RoleView } from '@/api/types';
import { useToastStore } from '@/stores/toast';
import { useApiError } from '@/composables/useApiError';
import { useFormatters } from '@/composables/useFormatters';
import AppIcon from '@/components/AppIcon.vue';
import ConfirmDialog from '@/components/ConfirmDialog.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import FormField from '@/components/FormField.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import ModalDialog from '@/components/ModalDialog.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrivilegePicker from '@/components/PrivilegePicker.vue';
import StatusBadge from '@/components/StatusBadge.vue';

const { t } = useI18n();
const toast = useToastStore();
const apiError = useApiError();
const fmt = useFormatters();

const loading = ref(true);
const error = ref<unknown>(null);
const roles = ref<RoleView[]>([]);
const wellKnown = ref<string[]>([]);
const repositoryNames = ref<string[]>([]);

const editing = ref<RoleView | null>(null);
const dialogOpen = ref(false);
const dialogError = ref<unknown>(null);
const saving = ref(false);
const pendingDelete = ref<RoleView | null>(null);
const deleting = ref(false);

const form = reactive({ name: '', description: '', privileges: [] as string[] });
const localErrors = ref<Record<string, string>>({});
const fieldErrors = computed(() => ({ ...apiError.fields(dialogError.value), ...localErrors.value }));
const generalError = computed(() => (dialogError.value && !Object.keys(apiError.fields(dialogError.value)).length ? dialogError.value : null));

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [r, p, repos] = await Promise.all([api.roles.list(), api.roles.privileges(), api.repositories.list()]);
    roles.value = r;
    wellKnown.value = p;
    repositoryNames.value = repos.map((x) => x.name).sort();
  } catch (e) {
    error.value = e;
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  editing.value = null;
  Object.assign(form, { name: '', description: '', privileges: [] });
  localErrors.value = {};
  dialogError.value = null;
  dialogOpen.value = true;
}

function openEdit(role: RoleView): void {
  editing.value = role;
  Object.assign(form, { name: role.name, description: role.description ?? '', privileges: [...role.privileges] });
  localErrors.value = {};
  dialogError.value = null;
  dialogOpen.value = true;
}

function closeDialog(): void {
  if (!saving.value) {
    dialogOpen.value = false;
  }
}

async function submit(): Promise<void> {
  const found: Record<string, string> = {};
  if (!form.name.trim()) {
    found.name = t('validation.required');
  }
  localErrors.value = found;
  if (Object.keys(found).length) {
    return;
  }
  saving.value = true;
  dialogError.value = null;
  try {
    const body = { name: form.name.trim(), description: form.description.trim() || null, privileges: form.privileges };
    if (editing.value) {
      const updated = await api.roles.update(editing.value.name, body);
      roles.value = roles.value.map((r) => (r.name === updated.name ? updated : r));
      toast.success(t('roles.toasts.updated', { name: updated.name }));
    } else {
      const created = await api.roles.create(body);
      roles.value = [...roles.value, created].sort((a, b) => a.name.localeCompare(b.name));
      toast.success(t('roles.toasts.created', { name: created.name }));
    }
    dialogOpen.value = false;
  } catch (e) {
    dialogError.value = e;
  } finally {
    saving.value = false;
  }
}

async function confirmDelete(): Promise<void> {
  const role = pendingDelete.value;
  if (!role) {
    return;
  }
  deleting.value = true;
  try {
    await api.roles.delete(role.name);
    roles.value = roles.value.filter((r) => r.name !== role.name);
    toast.success(t('roles.toasts.deleted', { name: role.name }));
    pendingDelete.value = null;
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    deleting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader :title="t('roles.title')" :subtitle="t('roles.subtitle')">
      <button type="button" class="btn btn--primary" @click="openCreate">
        <AppIcon name="plus" :size="16" />
        {{ t('roles.new') }}
      </button>
    </PageHeader>

    <ErrorAlert :error="error" />
    <LoadingIndicator v-if="loading" />

    <div v-else class="card">
      <EmptyState v-if="!roles.length" :title="t('roles.empty')" />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th scope="col">{{ t('roles.columns.name') }}</th>
              <th scope="col">{{ t('roles.columns.description') }}</th>
              <th scope="col">{{ t('roles.columns.privileges') }}</th>
              <th scope="col" class="actions"><span class="visually-hidden">{{ t('common.actions') }}</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="role in roles" :key="role.id" :data-role="role.name">
              <td>
                <strong>{{ role.name }}</strong>
                <StatusBadge v-if="role.builtin" plain style="margin-inline-start: 0.4rem" :title="t('roles.builtinHint')">{{ t('roles.builtin') }}</StatusBadge>
              </td>
              <td class="muted">{{ role.description || '—' }}</td>
              <td>
                <details v-if="role.privileges.length" class="small">
                  <summary style="cursor: pointer">{{ t('roles.privilegeCount', { count: fmt.number(role.privileges.length) }) }}</summary>
                  <span class="cluster" style="margin-top: 0.35rem">
                    <code v-for="p in role.privileges" :key="p">{{ p }}</code>
                  </span>
                </details>
                <span v-else class="faint">—</span>
              </td>
              <td class="actions">
                <span class="cluster">
                  <button type="button" class="btn btn--ghost btn--icon btn--sm" :aria-label="t('common.edit')" :title="t('common.edit')" @click="openEdit(role)">
                    <AppIcon name="edit" :size="15" />
                  </button>
                  <button v-if="!role.builtin" type="button" class="btn btn--danger-ghost btn--icon btn--sm" :aria-label="t('common.delete')" :title="t('common.delete')" @click="pendingDelete = role">
                    <AppIcon name="trash" :size="15" />
                  </button>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <ModalDialog :open="dialogOpen" :title="editing ? t('roles.editName', { name: editing.name }) : t('roles.create')" wide @close="closeDialog">
      <form id="role-form" class="stack" novalidate @submit.prevent="submit">
        <ErrorAlert :error="generalError" />
        <div class="form-grid">
          <FormField id="role-name" :label="t('roles.fields.name')" :hint="editing ? undefined : t('roles.hints.name')" :error="fieldErrors.name" required>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="form.name" class="input" type="text" autocomplete="off" :readonly="editing !== null" required maxlength="100" :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
          <FormField id="role-description" :label="t('roles.fields.description')" :error="fieldErrors.description" optional>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="form.description" class="input" type="text" maxlength="500" :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
        </div>
        <div v-if="fieldErrors.privileges" class="field__error" role="alert">{{ fieldErrors.privileges }}</div>
        <PrivilegePicker v-model="form.privileges" :well-known="wellKnown" :repositories="repositoryNames" />
      </form>
      <template #footer>
        <button type="button" class="btn" :disabled="saving" @click="closeDialog">{{ t('common.cancel') }}</button>
        <button type="submit" form="role-form" class="btn btn--primary" :disabled="saving">
          <span v-if="saving" class="spinner" aria-hidden="true"></span>
          {{ editing ? t('common.save') : t('common.create') }}
        </button>
      </template>
    </ModalDialog>

    <ConfirmDialog
      :open="pendingDelete !== null"
      :title="t('common.delete')"
      :message="t('roles.confirmDelete', { name: pendingDelete?.name ?? '' })"
      :confirm-label="t('common.delete')"
      danger
      :busy="deleting"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </div>
</template>
