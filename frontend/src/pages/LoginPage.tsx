import { useEffect } from 'react';
import { Auth } from '@supabase/auth-ui-react';
import { ThemeSupa } from '@supabase/auth-ui-shared';
import { Navigate } from 'react-router-dom';
import { supabase } from '../lib/supabaseClient';
import { useAuth } from '../auth/AuthContext';

export default function LoginPage() {
  const { session, loading } = useAuth();

  useEffect(() => {
    document.title = 'Login — MyFinanceView';
  }, []);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center text-content-secondary text-sm">
        Cargando…
      </div>
    );
  }

  if (session) {
    return <Navigate to="/transactions" replace />;
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-surface-base px-4">
      <div className="w-full max-w-md rounded-card border border-surface-border bg-surface-raised p-8 shadow-2xl">
        <header className="mb-6">
          <p className="text-content-muted uppercase tracking-[0.6px] text-[11px] font-semibold">
            MyFinanceView
          </p>
          <h1 className="mt-2 text-content-primary text-[22px] font-bold tracking-[-0.6px]">
            Inicia sesión
          </h1>
          <p className="mt-2 text-content-secondary text-sm">
            Usa tu email para entrar — recibirás un magic link o ingresa con tu contraseña.
          </p>
        </header>

        {/*
          D9 in design.md — force Sign In only, no Sign Up.
          - view="sign_in" picks the sign-in form on mount.
          - showLinks={false} hides "Don't have an account? Sign up".
          - providers=[] keeps it email/password + magic link only.
        */}
        <Auth
          supabaseClient={supabase}
          view="sign_in"
          showLinks={false}
          providers={[]}
          magicLink
          appearance={{
            theme: ThemeSupa,
            variables: {
              default: {
                colors: {
                  brand: '#7C5CFF',
                  brandAccent: '#22D3EE',
                  brandButtonText: '#0B0B0F',
                  inputBackground: '#15151C',
                  inputBorder: 'rgba(255,255,255,0.07)',
                  inputBorderHover: 'rgba(255,255,255,0.2)',
                  inputBorderFocus: '#7C5CFF',
                  inputText: '#F5F5F7',
                  inputLabelText: '#9CA3AF',
                  inputPlaceholder: '#6B7280',
                  messageText: '#9CA3AF',
                  messageTextDanger: '#FF6B6B',
                  anchorTextColor: '#22D3EE',
                  dividerBackground: 'rgba(255,255,255,0.07)'
                },
                fonts: {
                  bodyFontFamily: 'Geist, system-ui, sans-serif',
                  buttonFontFamily: 'Geist, system-ui, sans-serif',
                  inputFontFamily: 'Geist, system-ui, sans-serif',
                  labelFontFamily: 'Geist, system-ui, sans-serif'
                },
                radii: {
                  borderRadiusButton: '12px',
                  buttonBorderRadius: '12px',
                  inputBorderRadius: '12px'
                }
              }
            }
          }}
          localization={{
            variables: {
              sign_in: {
                email_label: 'Email',
                password_label: 'Contraseña',
                button_label: 'Iniciar sesión',
                loading_button_label: 'Iniciando sesión…',
                link_text: '',
                email_input_placeholder: 'tu@email.com',
                password_input_placeholder: 'Tu contraseña'
              },
              magic_link: {
                email_input_label: 'Email',
                email_input_placeholder: 'tu@email.com',
                button_label: 'Enviar magic link',
                loading_button_label: 'Enviando magic link…',
                link_text: '¿Prefieres un magic link? Enviar enlace.',
                confirmation_text: 'Revisa tu correo para iniciar sesión.'
              }
            }
          }}
        />
      </div>
    </div>
  );
}
