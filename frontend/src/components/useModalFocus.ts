import { useEffect, type RefObject } from 'react';

/** Keep keyboard focus in a modal and restore the trigger when it closes. */
export function useModalFocus(containerRef: RefObject<HTMLElement | null>, initialRef?: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const container = containerRef.current;
    (initialRef?.current ?? container)?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || !container) return;
      const controls = [...container.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
      )].filter((element) => !element.closest('[hidden], [inert]'));
      if (!controls.length) { event.preventDefault(); container.focus(); return; }
      const index = controls.indexOf(document.activeElement as HTMLElement);
      if (event.shiftKey && index <= 0) { event.preventDefault(); controls[controls.length - 1].focus(); }
      else if (!event.shiftKey && (index < 0 || index === controls.length - 1)) { event.preventDefault(); controls[0].focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => { document.removeEventListener('keydown', onKeyDown); previous?.focus(); };
  }, [containerRef, initialRef]);
}
