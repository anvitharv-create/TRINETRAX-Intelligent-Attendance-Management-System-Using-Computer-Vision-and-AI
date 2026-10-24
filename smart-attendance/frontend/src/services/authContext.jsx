import { createContext, useContext, useState, useEffect, useCallback } from "react";
import { authApi, api } from "./api";

const AuthContext = createContext();

export const useAuth = () => useContext(AuthContext);

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const storedToken = localStorage.getItem("access_token");
    const storedRole = localStorage.getItem("role");

    if (storedToken && storedRole) {
      api
        .get("/users/me")
        .then((res) => setUser(res.data))
        .catch(() => {
          authApi.logout();
          setUser(null);
        })
        .finally(() => setIsLoading(false));
    } else {
      setUser(null);
      setIsLoading(false);
    }
  }, []);

  const login = useCallback(async (email, password) => {
    const loggedIn = await authApi.login(email, password);
    setUser(loggedIn);
    return loggedIn;
  }, []);

  const refreshUser = useCallback(async () => {
    try {
      const data = await api.get("/users/me");
      setUser(data.data);
      return data.data;
    } catch (err) {
      return null;
    }
  }, []);

  const logout = useCallback(() => {
    authApi.logout();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, isLoading, login, refreshUser, logout }}>
      {children}
    </AuthContext.Provider>
  );
};