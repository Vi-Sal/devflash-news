// Adapted from shadcn/ui's Radix dialog foundation (MIT), with Glacier Glass styling.
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ComponentProps } from 'react';

export function DialogContent({
  children,
  className = '',
  ...props
}: ComponentProps<typeof DialogPrimitive.Content>) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="dialog-overlay" />
      <DialogPrimitive.Content
        data-slot="dialog-content"
        className={'glass ' + className}
        {...props}
      >
        {children}
        <DialogPrimitive.Close className="icon-only close" aria-label="Close">
          <X size={18} />
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
