import { toast, type ToastOptions } from 'react-toastify';

export type NotifyOptions = ToastOptions;

export const notify = {
  success: (content: string, options?: NotifyOptions) => toast.success(content, options),
  error: (content: string, options?: NotifyOptions) => toast.error(content, options),
  warning: (content: string, options?: NotifyOptions) => toast.warning(content, options),
  info: (content: string, options?: NotifyOptions) => toast.info(content, options),
};

export default notify;
