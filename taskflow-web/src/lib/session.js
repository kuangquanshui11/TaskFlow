let tokenProvider = async () => null;
let unauthorized = () => {};
export function configureSession({ getToken, onUnauthorized }) {
  tokenProvider = getToken;
  unauthorized = onUnauthorized;
}
export const accessToken = () => tokenProvider();
export const sessionRejected = (token) => unauthorized(token);
