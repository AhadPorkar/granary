<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { RoleView, UserView } from '@/api/types';
import { useAuthStore } from '@/stores/auth';
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
import StatusBadge from '@/components/StatusBadge.vue';
import ToggleSwitch from '@/components/ToggleSwitch.vue';

const MIN_PASSWORD = 8;

const { t } = useI18n();
const auth = useAuthStore();
const toast = useToastStore();
const apiError = useApiError();
const fmt = useFormatters();

const loading = ref(true);
const error = ref<unknown>(null);
const users = ref<UserView[]>([]);
const roles = ref<RoleView[]>([]);

type Dialog = { kind: 'create' } | { kind: 'edit'; user: UserView } | { kind: 'password'; user: UserView } | null;
const dialog = ref<Dialog>(null);
const dialogError = ref<unknown>(null);
const saving = ref(false);
const pendingDelete = ref<UserView | null>(null);
const deleting = ref(false);
const busy = ref<string | null>(null);

const form = reactive({
  username: '',
  password: '',
  displayName: '',
  email: '',
  enabled: true,
  roles: [] as string[],
});
const localErrors = ref<Record<string, string>>({});
const fieldErrors = computed(() => ({ ...apiError.fields(dialogError.value), ...localErrors.value }));
const generalError = computed(() => (dialogError.value && !Object.keys(apiError.fields(dialogError.value)).length ? dialogError.value : null));

const dialogTitle = computed(() => {
  switch (dialog.value?.kind) {
    case 'create':
      return t('users.create');
    case 'edit':
      return t('users.editName', { name: dialog.value.user.username });
    case 'password':
      return t('users.setPasswordFor', { name: dialog.value.user.username });
    default:
      return '';
  }
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [u, r] = await Promise.all([api.users.list(), api.roles.list()]);
    users.value = u;
    roles.value = r;
  } catch (e) {
    error.value = e;
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  Object.assign(form, { username: '', password: '', displayName: '', email: '', enabled: true, roles: [] });
  localErrors.value = {};
  dialogError.value = null;
  dialog.value = { kind: 'create' };
}

function openEdit(user: UserView): void {
  Object.assign(form, { username: user.username, password: '', displayName: user.displayName ?? '', email: user.email ?? '', enabled: user.enabled, roles: [...user.roles] });
  localErrors.value = {};
  dialogError.value = null;
  dialog.value = { kind: 'edit', user };
}

function openPassword(user: UserView): void {
  form.password = '';
  localErrors.value = {};
  dialogError.value = null;
  dialog.value = { kind: 'password', user };
}

function closeDialog(): void {
  if (!saving.value) {
    dialog.value = null;
  }
}

function replaceUser(updated: UserView): void {
  users.value = users.value.map((u) => (u.username === updated.username ? updated : u));
}

function validatePassword(): boolean {
  const found: Record<string, string> = {};
  if (form.password.length < MIN_PASSWORD) {
    found.password = t('validation.minLength', { min: fmt.number(MIN_PASSWORD) });
  }
  localErrors.value = found;
  return !Object.keys(found).length;
}

async function submitDialog(): Promise<void> {
  const current = dialog.value;
  if (!current) {
    return;
  }
  saving.value = true;
  dialogError.value = null;
  try {
    if (current.kind === 'create') {
      const found: Record<string, string> = {};
      if (!form.username.trim()) {
        found.username = t('validation.required');
      }
      if (form.password.length < MIN_PASSWORD) {
        found.password = t('validation.minLength', { min: fmt.number(MIN_PASSWORD) });
      }
      localErrors.value = found;
      if (Object.keys(found).length) {
        return;
      }
      const created = await api.users.create({
        username: form.username.trim(),
        password: form.password,
        displayName: form.displayName.trim() || null,
        email: form.email.trim() || null,
        roles: form.roles,
      });
      users.value = [...users.value, created].sort((a, b) => a.username.localeCompare(b.username));
      toast.success(t('users.toasts.created', { name: created.username }));
    } else if (current.kind === 'edit') {
      const updated = await api.users.update(current.user.username, {
        displayName: form.displayName.trim() || null,
        email: form.email.trim() || null,
        enabled: form.enabled,
        roles: form.roles,
      });
      replaceUser(updated);
      toast.success(t('users.toasts.updated', { name: updated.username }));
    } else {
      if (!validatePassword()) {
        return;
      }
      await api.users.setPassword(current.user.username, form.password);
      toast.success(t('users.toasts.passwordSet', { name: current.user.username }));
    }
    dialog.value = null;
  } catch (e) {
    dialogError.value = e;
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(user: UserView): Promise<void> {
  busy.value = user.username;
  try {
    const updated = await api.users.update(user.username, { enabled: !user.enabled });
    replaceUser(updated);
    toast.success(t(updated.enabled ? 'users.toasts.enabled' : 'users.toasts.disabled', { name: user.username }));
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    busy.value = null;
  }
}

async function confirmDelete(): Promise<void> {
  const user = pendingDelete.value;
  if (!user) {
    return;
  }
  deleting.value = true;
  try {
    await api.users.delete(user.username);
    users.value = users.value.filter((u) => u.username !== user.username);
    toast.success(t('users.toasts.deleted', { name: user.username }));
    pendingDelete.value = null;
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    deleting.value = false;
  }
}

function toggleRole(name: string, on: boolean): void {
  form.roles = on ? [...new Set([...form.roles, name])] : form.roles.filter((r) => r !== name);
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader :title="t('users.title')" :subtitle="t('users.subtitle')">
      <button type="button" class="btn btn--primary" @click="openCreate">
        <AppIcon name="plus" :size="16" />
        {{ t('users.new') }}
      </button>
    </PageHeader>

    <ErrorAlert :error="error" />
    <LoadingIndicator v-if="loading" />

    <div v-else class="card">
      <EmptyState v-if="!users.length" :title="t('users.empty')" />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th scope="col">{{ t('users.columns.username') }}</th>
              <th scope="col">{{ t('users.columns.displayName') }}</th>
              <th scope="col">{{ t('users.columns.email') }}</th>
              <th scope="col">{{ t('users.columns.roles') }}</th>
              <th scope="col">{{ t('users.columns.status') }}</th>
              <th scope="col">{{ t('users.columns.created') }}</th>
              <th scope="col" class="actions"><span class="visually-hidden">{{ t('common.actions') }}</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id" :data-user="user.username">
              <td>
                <strong>{{ user.username }}</strong>
                <span v-if="user.username === auth.username" class="faint small"> ({{ t('users.you') }})</span>
                <StatusBadge v-if="user.builtin" plain style="margin-inline-start: 0.4rem">{{ t('users.builtin') }}</StatusBadge>
              </td>
              <td :class="{ 'table__cell--stack-hidden': !user.displayName }"><bdi>{{ user.displayName || '—' }}</bdi></td>
              <td :class="{ 'table__cell--stack-hidden': !user.email }"><span class="ltr">{{ user.email || '—' }}</span></td>
              <td>
                <span class="cluster">
                  <StatusBadge v-for="role in user.roles" :key="role" plain variant="info">{{ role }}</StatusBadge>
                  <span v-if="!user.roles.length" class="faint">—</span>
                </span>
              </td>
              <td>
                <StatusBadge :variant="user.enabled ? 'success' : 'danger'">{{ user.enabled ? t('common.enabled') : t('common.disabled') }}</StatusBadge>
              </td>
              <td class="nowrap muted small" :data-label="t('users.columns.created')">{{ fmt.date(user.createdAt) }}</td>
              <td class="actions">
                <span class="cluster">
                  <button type="button" class="btn btn--ghost btn--icon btn--sm" :aria-label="t('users.actions.editName', { name: user.username })" :title="t('common.edit')" @click="openEdit(user)">
                    <AppIcon name="edit" :size="15" />
                  </button>
                  <button type="button" class="btn btn--ghost btn--icon btn--sm" :aria-label="t('users.actions.setPasswordName', { name: user.username })" :title="t('users.setPassword')" @click="openPassword(user)">
                    <AppIcon name="key" :size="15" />
                  </button>
                  <button
                    v-if="user.username !== auth.username && !(user.builtin && user.enabled)"
                    type="button"
                    class="btn btn--ghost btn--sm"
                    :disabled="busy === user.username"
                    @click="toggleEnabled(user)"
                  >
                    {{ user.enabled ? t('users.actions.disable') : t('users.actions.enable') }}
                  </button>
                  <button v-if="user.username !== auth.username && !user.builtin" type="button" class="btn btn--danger-ghost btn--icon btn--sm" :aria-label="t('users.actions.deleteName', { name: user.username })" :title="t('common.delete')" @click="pendingDelete = user">
                    <AppIcon name="trash" :size="15" />
                  </button>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <ModalDialog :open="dialog !== null" :title="dialogTitle" @close="closeDialog">
      <form id="user-form" class="stack" novalidate @submit.prevent="submitDialog">
        <ErrorAlert :error="generalError" />

        <template v-if="dialog?.kind === 'create' || dialog?.kind === 'edit'">
          <FormField v-if="dialog.kind === 'create'" id="user-username" :label="t('users.fields.username')" :hint="t('users.hints.username')" :error="fieldErrors.username" required>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="form.username" class="input" type="text" autocomplete="off" autocapitalize="none" required maxlength="100" :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
          <FormField v-if="dialog.kind === 'create'" id="user-password" :label="t('users.fields.password')" :hint="t('users.hints.password', { min: fmt.number(MIN_PASSWORD) })" :error="fieldErrors.password" required>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="form.password" class="input" type="password" autocomplete="new-password" required :minlength="MIN_PASSWORD" :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
          <div class="form-grid">
            <FormField id="user-display-name" :label="t('users.fields.displayName')" :error="fieldErrors.displayName" optional>
              <template #default="{ id, invalid, describedBy }">
                <input :id="id" v-model="form.displayName" class="input" type="text" maxlength="200" :aria-invalid="invalid" :aria-describedby="describedBy" />
              </template>
            </FormField>
            <FormField id="user-email" :label="t('users.fields.email')" :error="fieldErrors.email" optional>
              <template #default="{ id, invalid, describedBy }">
                <input :id="id" v-model="form.email" class="input ltr" type="email" maxlength="320" :aria-invalid="invalid" :aria-describedby="describedBy" />
              </template>
            </FormField>
          </div>
          <fieldset class="field" style="border: 0; padding: 0; margin: 0">
            <legend class="field__label">{{ t('users.fields.roles') }}</legend>
            <div class="privilege-grid">
              <label v-for="role in roles" :key="role.name" class="checkbox">
                <input type="checkbox" :value="role.name" :checked="form.roles.includes(role.name)" @change="toggleRole(role.name, ($event.target as HTMLInputElement).checked)" />
                <span class="checkbox__text">
                  <span>{{ role.name }}</span>
                  <span v-if="role.description" class="field__hint">{{ role.description }}</span>
                </span>
              </label>
            </div>
            <div v-if="fieldErrors.roles" class="field__error">{{ fieldErrors.roles }}</div>
          </fieldset>
          <ToggleSwitch
            v-if="dialog.kind === 'edit'"
            id="user-enabled"
            v-model="form.enabled"
            :label="t('users.fields.enabled')"
            :hint="t('users.hints.enabled')"
            :disabled="dialog.user.username === auth.username || dialog.user.builtin"
          />
        </template>

        <FormField v-else-if="dialog?.kind === 'password'" id="user-new-password" :label="t('users.fields.password')" :hint="t('users.hints.password', { min: fmt.number(MIN_PASSWORD) })" :error="fieldErrors.password" required>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="form.password" class="input" type="password" autocomplete="new-password" required :minlength="MIN_PASSWORD" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
      </form>
      <template #footer>
        <button type="button" class="btn" :disabled="saving" @click="closeDialog">{{ t('common.cancel') }}</button>
        <button type="submit" form="user-form" class="btn btn--primary" :disabled="saving">
          <span v-if="saving" class="spinner" aria-hidden="true"></span>
          {{ dialog?.kind === 'create' ? t('common.create') : t('common.save') }}
        </button>
      </template>
    </ModalDialog>

    <ConfirmDialog
      :open="pendingDelete !== null"
      :title="t('common.delete')"
      :message="t('users.confirmDelete', { name: pendingDelete?.username ?? '' })"
      :confirm-label="t('common.delete')"
      danger
      :busy="deleting"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </div>
</template>
