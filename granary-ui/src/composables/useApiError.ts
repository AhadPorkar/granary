import { useI18n } from 'vue-i18n';
import { fieldErrors, translateError } from '@/utils/errors';

/** Error translation bound to the active vue-i18n instance. */
export function useApiError() {
  const { t, te } = useI18n();
  const translator = {
    t: (key: string, values?: Record<string, unknown>) => (values ? t(key, values) : t(key)),
    te: (key: string) => te(key),
  };
  return {
    message: (error: unknown) => translateError(error, translator),
    fields: (error: unknown) => fieldErrors(error, translator),
  };
}
