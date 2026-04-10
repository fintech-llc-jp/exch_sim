// Cloudflare Workersからexch_sim APIを呼び出すサンプル
// デプロイ方法: wrangler publish または Cloudflare Dashboardから

// exch_sim APIのベースURL（環境変数から取得、デフォルトはlocalhost）
const EXCH_SIM_API_URL = 'https://your-exch-sim-domain.com'; // 本番環境のURLに変更

/**
 * ログインしてJWTトークンを取得
 */
async function login(username, password) {
  const response = await fetch(`${EXCH_SIM_API_URL}/api/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      username: username,
      password: password,
    }),
  });

  if (!response.ok) {
    throw new Error(`Login failed: ${response.statusText}`);
  }

  const data = await response.json();
  return data.token;
}

/**
 * JWTトークンを使ってAPIを呼び出す
 */
async function callExchSimAPI(endpoint, method = 'GET', body = null, token = null) {
  const headers = {
    'Content-Type': 'application/json',
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const options = {
    method: method,
    headers: headers,
  };

  if (body) {
    options.body = JSON.stringify(body);
  }

  const response = await fetch(`${EXCH_SIM_API_URL}${endpoint}`, options);

  if (!response.ok) {
    throw new Error(`API call failed: ${response.statusText}`);
  }

  return await response.json();
}

/**
 * Cloudflare Workersのメインエントリーポイント
 */
export default {
  async fetch(request, env, ctx) {
    // CORSヘッダー
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    };

    // OPTIONSリクエスト（プリフライト）の処理
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: corsHeaders,
      });
    }

    try {
      const url = new URL(request.url);
      const path = url.pathname;

      // ルーティング
      switch (path) {
        case '/api/login':
          // ログインエンドポイント（プロキシ）
          if (request.method === 'POST') {
            const body = await request.json();
            const token = await login(body.username, body.password);
            return new Response(JSON.stringify({ token }), {
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }
          break;

        case '/api/board':
          // 板情報取得（認証不要）
          const symbol = url.searchParams.get('symbol') || 'G_FX_BTCJPY';
          const boardData = await callExchSimAPI(`/api/market/board/${symbol}`);
          return new Response(JSON.stringify(boardData), {
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });

        case '/api/orders':
          // 注文一覧取得（認証必要）
          if (request.method === 'GET') {
            const authHeader = request.headers.get('Authorization');
            if (!authHeader) {
              return new Response(JSON.stringify({ error: 'Authorization required' }), {
                status: 401,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }

            const token = authHeader.replace('Bearer ', '');
            const symbol = url.searchParams.get('symbol');
            const status = url.searchParams.get('status');
            
            let endpoint = '/api/orders/list';
            if (symbol || status) {
              const params = new URLSearchParams();
              if (symbol) params.append('symbol', symbol);
              if (status) params.append('status', status);
              endpoint += `?${params.toString()}`;
            }

            const orders = await callExchSimAPI(endpoint, 'GET', null, token);
            return new Response(JSON.stringify(orders), {
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }

          // 新規注文（認証必要）
          if (request.method === 'POST') {
            const authHeader = request.headers.get('Authorization');
            if (!authHeader) {
              return new Response(JSON.stringify({ error: 'Authorization required' }), {
                status: 401,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }

            const token = authHeader.replace('Bearer ', '');
            const body = await request.json();
            const result = await callExchSimAPI('/api/orders/new', 'POST', body, token);
            return new Response(JSON.stringify(result), {
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }
          break;

        case '/api/positions':
          // ポジション取得（認証必要）
          if (request.method === 'GET') {
            const authHeader = request.headers.get('Authorization');
            if (!authHeader) {
              return new Response(JSON.stringify({ error: 'Authorization required' }), {
                status: 401,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }

            const token = authHeader.replace('Bearer ', '');
            const type = url.searchParams.get('type') || 'summary'; // summary, list, trades
            
            let endpoint = '/api/positions';
            if (type === 'summary') {
              endpoint += '/summary';
            } else if (type === 'trades') {
              endpoint += '/trades';
              const limit = url.searchParams.get('limit');
              const symbol = url.searchParams.get('symbol');
              if (limit || symbol) {
                const params = new URLSearchParams();
                if (limit) params.append('limit', limit);
                if (symbol) params.append('symbol', symbol);
                endpoint += `?${params.toString()}`;
              }
            } else {
              const symbol = url.searchParams.get('symbol');
              if (symbol) {
                endpoint += `/${symbol}`;
              }
            }

            const positions = await callExchSimAPI(endpoint, 'GET', null, token);
            return new Response(JSON.stringify(positions), {
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }
          break;

        default:
          return new Response(JSON.stringify({ 
            error: 'Not found',
            availableEndpoints: [
              'POST /api/login',
              'GET /api/board?symbol=G_FX_BTCJPY',
              'GET /api/orders?symbol=...&status=...',
              'POST /api/orders',
              'GET /api/positions?type=summary|list|trades',
            ]
          }), {
            status: 404,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
      }
    } catch (error) {
      return new Response(JSON.stringify({ 
        error: error.message 
      }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }
  },
};









